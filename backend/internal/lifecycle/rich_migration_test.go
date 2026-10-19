package lifecycle

import (
	"context"
	"database/sql"
	"strings"
	"testing"
)

func TestRichMigrationPreservesOriginalsPendingWorkAndPushReferences(t *testing.T) {
	path, sender, reader := versionOneDatabase(t, "2026-01-01T12:00:00Z")
	db, err := sql.Open("sqlite", databaseURL(path))
	if err != nil {
		t.Fatal(err)
	}
	legacy := &Lifecycle{db: db}
	err = legacy.transaction(context.Background(), func(conn *sql.Conn) error {
		if err := migrateHistory(context.Background(), conn); err != nil {
			return err
		}
		if _, err := conn.ExecContext(context.Background(), pushSchema); err != nil {
			return err
		}
		_, err := conn.ExecContext(context.Background(), pushWorkSchema)
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	registration, err := legacy.PutPush(context.Background(), reader.Secret, PushMutation{Version: 0, Key: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}, []byte(`{"registrationId":"11111111111111111111111111111111","provider":"fcm","destination":"synthetic-token"}`))
	if err != nil {
		t.Fatal(err)
	}
	arrival, _, err := legacy.Submit(context.Background(), sender.Secret, []byte(strings.Replace(validAlert, "nas-1", "later", 1)))
	if err != nil {
		t.Fatal(err)
	}
	legacy.Close()
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	got, err := store.Read(context.Background(), reader.Secret, "old-alert")
	if err != nil || got.Original.Title != "Disk pressure" || got.Presentation.Revision != 0 {
		t.Fatalf("original lost: %+v %v", got, err)
	}
	current, err := store.PushRegistration(context.Background(), reader.Secret)
	if err != nil || current.Version != registration.Version || !current.Enabled {
		t.Fatalf("registration lost: %+v %v", current, err)
	}
	changes, err := store.Changes(context.Background(), reader.Secret, ChangesQuery{Cursor: *registration.BaselineCursor})
	if err != nil || len(changes.Items) != 1 || changes.Items[0].AlertID != arrival.ID {
		t.Fatalf("changes lost: %+v %v", changes, err)
	}
	var foreignKeys int
	if err = store.db.QueryRow("PRAGMA foreign_keys").Scan(&foreignKeys); err != nil || foreignKeys != 1 {
		t.Fatalf("foreign keys disabled: %d %v", foreignKeys, err)
	}
	runWorker(t, store)
	waitFallback(t, store, reader.Secret, arrival.ID)
}
