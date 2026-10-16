package app

import (
	"context"
	"database/sql"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"
)

type stalledDiagnostics struct {
	entered chan struct{}
	release chan struct{}
	once    sync.Once
	failed  bool
}

func (w *stalledDiagnostics) Write(data []byte) (int, error) {
	w.once.Do(func() { close(w.entered) })
	if w.failed {
		return 0, io.ErrClosedPipe
	}
	<-w.release
	return len(data), nil
}

func holdBackendWriter(t *testing.T, database string) func() {
	t.Helper()
	db, err := sql.Open("sqlite", database)
	if err != nil {
		t.Fatal(err)
	}
	conn, err := db.Conn(context.Background())
	if err != nil {
		db.Close()
		t.Fatal(err)
	}
	if _, err = conn.ExecContext(context.Background(), "PRAGMA busy_timeout=5000"); err != nil {
		t.Fatal(err)
	}
	if _, err = conn.ExecContext(context.Background(), "BEGIN IMMEDIATE"); err != nil {
		conn.Close()
		db.Close()
		t.Fatal(err)
	}
	var once sync.Once
	release := func() {
		once.Do(func() { _, _ = conn.ExecContext(context.Background(), "ROLLBACK"); conn.Close(); db.Close() })
	}
	t.Cleanup(release)
	return release
}

func TestUnavailableDiagnosticsDoNotBlockProcessingOrShutdown(t *testing.T) {
	for _, mode := range []string{"stalled", "failed"} {
		t.Run(mode, func(t *testing.T) {
			sink := &stalledDiagnostics{entered: make(chan struct{}), release: make(chan struct{}), failed: mode == "failed"}
			defer close(sink.release)
			backend := startBackend(t, sink)
			release := holdBackendWriter(t, backend.database)
			select {
			case <-sink.entered:
			case <-time.After(3 * time.Second):
				t.Fatal("missing recovery diagnostic")
			}
			release()
			client := &http.Client{Timeout: 7 * time.Second}
			original := `{"schemaVersion":1,"eventId":"synthetic","title":"Synthetic","summary":"SENTINEL-private-original","priority":"normal","observedAt":"2026-01-01T12:00:00Z"}`
			request, err := http.NewRequest("POST", backend.address+"/v1/alerts", strings.NewReader(original))
			if err != nil {
				t.Fatal(err)
			}
			request.Header.Set("Authorization", "Bearer "+backend.sender)
			request.Header.Set("Content-Type", "application/json")
			response, err := client.Do(request)
			if err != nil {
				t.Fatal(err)
			}
			var accepted struct{ ID string }
			err = json.NewDecoder(response.Body).Decode(&accepted)
			response.Body.Close()
			if err != nil || response.StatusCode != 201 {
				t.Fatal("acceptance failed")
			}
			deadline := time.Now().Add(3 * time.Second)
			completed := false
			for time.Now().Before(deadline) {
				request, _ = http.NewRequest("GET", backend.address+"/v1/alerts/"+accepted.ID, nil)
				request.Header.Set("Authorization", "Bearer "+backend.reader)
				response, err = client.Do(request)
				if err != nil {
					t.Fatal(err)
				}
				var alert struct {
					Original     struct{ Summary string }
					Presentation struct{ State string }
				}
				err = json.NewDecoder(response.Body).Decode(&alert)
				response.Body.Close()
				if err != nil || response.StatusCode != 200 {
					t.Fatal("read failed")
				}
				if alert.Presentation.State == "fallback" {
					if alert.Original.Summary != "SENTINEL-private-original" {
						t.Fatal("original changed")
					}
					completed = true
					break
				}
				time.Sleep(10 * time.Millisecond)
			}
			if !completed {
				t.Fatal("blocked diagnostic stopped fallback processing")
			}
			started := time.Now()
			backend.stop(t)
			if elapsed := time.Since(started); elapsed > 1500*time.Millisecond {
				t.Fatalf("diagnostic blocked shutdown: %v", elapsed)
			}
		})
	}
}

var _ io.Writer = (*stalledDiagnostics)(nil)
