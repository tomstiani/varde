package app

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

type runningBackend struct {
	address  string
	database string
	sender   string
	reader   string
	cancel   context.CancelFunc
	done     chan error
	stopOnce sync.Once
}

func (backend *runningBackend) stop(t *testing.T) {
	t.Helper()
	backend.stopOnce.Do(func() {
		backend.cancel()
		select {
		case err := <-backend.done:
			if err != nil {
				t.Error(err)
			}
		case <-time.After(6 * time.Second):
			t.Error("shutdown exceeded budget")
		}
	})
}

func startBackend(t *testing.T, diagnostics io.Writer) *runningBackend {
	t.Helper()
	database := filepath.Join(t.TempDir(), "synthetic.sqlite")
	store, err := lifecycle.Open(database)
	if err != nil {
		t.Fatal(err)
	}
	issue := func(role lifecycle.Role) string {
		identity, err := store.CreateIdentity(context.Background(), role, "synthetic")
		if err != nil {
			t.Fatal(err)
		}
		credential, err := store.IssueCredential(context.Background(), identity.ID)
		if err != nil {
			t.Fatal(err)
		}
		return credential.Secret
	}
	backend := &runningBackend{database: database, sender: issue(lifecycle.Sender), reader: issue(lifecycle.Installation), done: make(chan error, 1)}
	store.Close()
	ctx, cancel := context.WithCancel(context.Background())
	backend.cancel = cancel
	output, input := io.Pipe()
	go func() {
		backend.done <- Run(ctx, []string{"-db", database, "serve", "-listen", "127.0.0.1:0"}, input, diagnostics)
		input.Close()
	}()
	ready := make(chan string, 1)
	go func() {
		var record struct{ Address string }
		json.NewDecoder(output).Decode(&record)
		ready <- record.Address
	}()
	select {
	case address := <-ready:
		if address == "" {
			t.Fatal("backend not ready")
		}
		backend.address = "http://" + address
	case <-time.After(3 * time.Second):
		cancel()
		t.Fatal("readiness timeout")
	}
	t.Cleanup(func() {
		backend.stop(t)
		output.Close()
	})
	return backend
}

func TestRequestBudgetIncludesAStalledSubmissionBody(t *testing.T) {
	backend := startBackend(t, io.Discard)
	connection, err := net.Dial("tcp", strings.TrimPrefix(backend.address, "http://"))
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	if err = connection.SetDeadline(time.Now().Add(8 * time.Second)); err != nil {
		t.Fatal(err)
	}
	started := time.Now()
	_, err = fmt.Fprintf(connection, "POST /v1/alerts HTTP/1.1\r\nHost: synthetic\r\nAuthorization: Bearer %s\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n{\"summary\":\"SENTINEL-private-original\"", backend.sender)
	if err != nil {
		t.Fatal(err)
	}
	response, err := http.ReadResponse(bufio.NewReader(connection), nil)
	if err != nil {
		t.Fatal("request did not receive bounded safe failure:", err)
	}
	defer response.Body.Close()
	payload, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if elapsed := time.Since(started); elapsed > 6500*time.Millisecond {
		t.Fatalf("response exceeded five-second budget with tolerance: %v", elapsed)
	}
	if response.StatusCode != 503 || response.Header.Get("Content-Type") != "application/json" || response.Header.Get("Retry-After") != "5" || response.Header.Get("Cache-Control") != "no-store" {
		t.Fatalf("unsafe timeout response: status=%d headers=%v", response.StatusCode, response.Header)
	}
	var failure struct{ Error string }
	if json.Unmarshal(payload, &failure) != nil || failure.Error != "storage_unavailable" || strings.Contains(string(payload), "SENTINEL") {
		t.Fatal("timeout response leaked or broke JSON error contract")
	}
}
