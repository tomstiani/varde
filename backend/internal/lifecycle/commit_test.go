package lifecycle

import (
	"context"
	"database/sql"
	"testing"
)

func TestFailedCommitLeavesNoPartialAcceptance(t *testing.T) {
	f := setup(t)
	f.store.beforeAcceptCommit = func(conn *sql.Conn) error {
		if _, err := conn.ExecContext(context.Background(), "PRAGMA defer_foreign_keys=ON"); err != nil {
			return err
		}
		// INSERT succeeds; the deferred foreign-key violation rejects COMMIT itself.
		_, err := conn.ExecContext(context.Background(), "INSERT INTO work(alert_id) VALUES('nonexistent')")
		if err != nil {
			t.Fatalf("expected deferred constraint: %v", err)
		}
		return err
	}
	_, created, err := f.store.Submit(context.Background(), f.sender.Secret, []byte(validAlert))
	category(t, err, "storage_unavailable")
	if created {
		t.Fatal("acknowledged failed commit")
	}
	counts(t, f.store, 0, 0, 0)

	f.store.beforeAcceptCommit = nil
	_, created, err = f.store.Submit(context.Background(), f.sender.Secret, []byte(validAlert))
	if err != nil || !created {
		t.Fatalf("retry after failed commit: %v %v", created, err)
	}
	counts(t, f.store, 1, 1, 1)
}
