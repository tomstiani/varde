package lifecycle

import (
	"context"
	"database/sql"
	"testing"
)

func TestFailedCommitLeavesNoPartialAcceptance(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	_, err := f.store.PutPush(ctx, f.reader.Secret, PushMutation{Version: 0, Key: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}, []byte(`{"registrationId":"11111111111111111111111111111111","provider":"fcm","destination":"PRIVATE"}`))
	if err != nil {
		t.Fatal(err)
	}
	provider := &recordedPushProvider{}
	dispatcher := f.store.NewPushDispatcher(provider, PushDispatchOptions{})
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
	report, dispatchErr := dispatcher.DispatchOne(ctx)
	if dispatchErr != nil || report.Status != "idle" || len(provider.messages) != 0 {
		t.Fatal("failed acceptance left dispatchable push work")
	}

	f.store.beforeAcceptCommit = nil
	_, created, err = f.store.Submit(context.Background(), f.sender.Secret, []byte(validAlert))
	if err != nil || !created {
		t.Fatalf("retry after failed commit: %v %v", created, err)
	}
	counts(t, f.store, 1, 1, 1)
	report, dispatchErr = dispatcher.DispatchOne(ctx)
	if dispatchErr != nil || report.Status != "delivered" || len(provider.messages) != 1 {
		t.Fatal("committed retry did not enqueue exactly one push")
	}
}
