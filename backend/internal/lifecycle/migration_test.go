package lifecycle

import (
	"context"
	"database/sql"
	"path/filepath"
	"strings"
	"testing"
)

func versionOneDatabase(t *testing.T, acceptedAt string) (string, Credential, Credential) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "version-one.sqlite")
	if err := prepareDatabaseFile(path); err != nil {
		t.Fatal(err)
	}
	db, err := sql.Open("sqlite", databaseURL(path))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.Exec(schema); err != nil {
		t.Fatal(err)
	}
	old := &Lifecycle{db: db}
	sender := newCredential(t, old, Sender)
	reader := newCredential(t, old, Installation)
	_, err = db.Exec(`INSERT INTO alerts(id,sender_id,event_id,accepted_at,original) VALUES('old-alert',?,'nas-1',?,?);
 INSERT INTO work(alert_id) VALUES('old-alert'); INSERT INTO changes(alert_id,revision) VALUES('old-alert',0);`, sender.IdentityID, acceptedAt, []byte(validAlert))
	if err != nil {
		t.Fatal(err)
	}
	return path, sender, reader
}

func TestHistoryMigrationPreservesVersionOneCredentialsOriginalsAndWork(t *testing.T) {
	path, sender, reader := versionOneDatabase(t, "2026-01-01T12:00:00.123Z")
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	ctx := context.Background()
	page, err := store.History(ctx, reader.Secret, HistoryQuery{Order: "acceptedAt"})
	if err != nil || len(page.Items) != 1 || page.Items[0].ID != "old-alert" || page.Items[0].AcceptedAt != "2026-01-01T12:00:00.123Z" {
		t.Fatalf("migrated history: %+v %v", page, err)
	}
	duplicate, created, err := store.Submit(ctx, sender.Secret, []byte(validAlert))
	if err != nil || created || duplicate.ID != "old-alert" {
		t.Fatalf("duplicate namespace lost: %+v %v", duplicate, err)
	}
	counts(t, store, 1, 1, 1)
	runWorker(t, store)
	alert := waitFallback(t, store, reader.Secret, "old-alert")
	if alert.Original.Summary != "Backup volume near limit" || alert.Presentation.Revision != 1 {
		t.Fatalf("original/work changed: %+v", alert)
	}
	counts(t, store, 1, 1, 2)
}

func TestFailedHistoryMigrationRollsBackSchemaAndCanBeRetried(t *testing.T) {
	path, _, reader := versionOneDatabase(t, "broken-original-timestamp")
	if store, err := Open(path); err == nil {
		store.Close()
		t.Fatal("corrupt timestamp migrated")
	}
	db, err := sql.Open("sqlite", databaseURL(path))
	if err != nil {
		t.Fatal(err)
	}
	var version, count int
	if err := db.QueryRow("PRAGMA user_version").Scan(&version); err != nil {
		t.Fatal(err)
	}
	if err := db.QueryRow("SELECT COUNT(*) FROM sqlite_master WHERE name='history_metadata'").Scan(&count); err != nil {
		t.Fatal(err)
	}
	if version != 1 || count != 0 {
		t.Fatalf("partial schema: version=%d metadata=%d", version, count)
	}
	if _, err := db.Exec("UPDATE alerts SET accepted_at='2026-01-01T00:00:00Z'"); err != nil {
		t.Fatal(err)
	}
	db.Close()
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	page, err := store.History(context.Background(), reader.Secret, HistoryQuery{})
	if err != nil || len(page.Items) != 1 {
		t.Fatalf("retry migration: %+v %v", page, err)
	}
}

func TestTimestampHistoryUsesItsIndex(t *testing.T) {
	f := setup(t)
	rows, err := f.store.db.Query("EXPLAIN QUERY PLAN SELECT id FROM alerts WHERE seq<=? AND (accepted_seconds,accepted_nanos,id)<(?,?,?) ORDER BY accepted_seconds DESC,accepted_nanos DESC,id DESC LIMIT 51", 100, 2000000000, 0, "alert_z")
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	found := false
	for rows.Next() {
		var id, parent, unused int
		var detail string
		if err := rows.Scan(&id, &parent, &unused, &detail); err != nil {
			t.Fatal(err)
		}
		if strings.Contains(detail, "alerts_by_instant") {
			found = true
		}
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	if !found {
		t.Fatal("timestamp continuation did not use the ordering index")
	}
}
