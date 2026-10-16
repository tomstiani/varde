package lifecycle

import (
	"context"
	"errors"
	"path/filepath"
	"testing"
	"time"
)

const validAlert = `{"schemaVersion":1,"eventId":"nas-1","title":"Disk pressure","summary":"Backup volume near limit","priority":"high","observedAt":"2026-01-01T14:00:00+02:00","data":[]}`

type fixture struct {
	store          *Lifecycle
	sender, reader Credential
}

func setup(t *testing.T) fixture {
	t.Helper()
	store, err := Open(filepath.Join(t.TempDir(), "varde.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { store.Close() })
	return fixture{store: store, sender: newCredential(t, store, Sender), reader: newCredential(t, store, Installation)}
}

func newCredential(t *testing.T, store *Lifecycle, role Role) Credential {
	t.Helper()
	identity, err := store.CreateIdentity(context.Background(), role, "test")
	if err != nil {
		t.Fatal(err)
	}
	credential, err := store.IssueCredential(context.Background(), identity.ID)
	if err != nil {
		t.Fatal(err)
	}
	return credential
}

func category(t *testing.T, err error, want string) {
	t.Helper()
	var failure *Failure
	if !errors.As(err, &failure) || failure.Category != want {
		t.Fatalf("error=%v want %s", err, want)
	}
}

func runWorker(t *testing.T, store *Lifecycle) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- store.Run(ctx, nil) }()
	t.Cleanup(func() {
		cancel()
		select {
		case err := <-done:
			if err != nil && !errors.Is(err, context.Canceled) {
				t.Error(err)
			}
		case <-time.After(5 * time.Second):
			t.Error("worker failed to stop")
		}
	})
}

func waitFallback(t *testing.T, store *Lifecycle, token, id string) Alert {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		alert, err := store.Read(context.Background(), token, id)
		if err != nil {
			t.Fatal(err)
		}
		if alert.Presentation.State == "fallback" {
			return alert
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatal("fallback not completed")
	return Alert{}
}

func counts(t *testing.T, store *Lifecycle, alerts, work, changes int) {
	t.Helper()
	for _, expected := range []struct {
		table string
		count int
	}{
		{"alerts", alerts},
		{"work", work},
		{"changes", changes},
	} {
		var count int
		if err := store.db.QueryRow("SELECT COUNT(*) FROM " + expected.table).Scan(&count); err != nil {
			t.Fatal(err)
		}
		if count != expected.count {
			t.Fatalf("%s=%d want %d", expected.table, count, expected.count)
		}
	}
}
