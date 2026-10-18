package lifecycle

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestChangePassKeepsItsHorizonAcrossConcurrentArrivals(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	checkpoint, err := f.store.Checkpoint(ctx, f.reader.Secret)
	if err != nil {
		t.Fatal(err)
	}
	var ids []string
	for i := 0; i < 3; i++ {
		alert, _, err := f.store.Submit(ctx, f.sender.Secret, []byte(strings.Replace(validAlert, "nas-1", fmt.Sprint(i), 1)))
		if err != nil {
			t.Fatal(err)
		}
		ids = append(ids, alert.ID)
	}
	first, err := f.store.Changes(ctx, f.reader.Secret, ChangesQuery{Cursor: checkpoint.Cursor, Limit: 1})
	if err != nil || len(first.Items) != 1 || !first.HasMore || first.Items[0].AlertID != ids[0] {
		t.Fatalf("first: %+v %v", first, err)
	}
	late, _, err := f.store.Submit(ctx, f.sender.Secret, []byte(strings.Replace(validAlert, "nas-1", "late", 1)))
	if err != nil {
		t.Fatal(err)
	}
	_, err = f.store.Changes(ctx, f.reader.Secret, ChangesQuery{Cursor: first.NextCursor, Limit: 2})
	category(t, err, "invalid_cursor")
	second, err := f.store.Changes(ctx, f.reader.Secret, ChangesQuery{Cursor: first.NextCursor, Limit: 1})
	if err != nil {
		t.Fatal(err)
	}
	third, err := f.store.Changes(ctx, f.reader.Secret, ChangesQuery{Cursor: second.NextCursor, Limit: 1})
	if err != nil || third.HasMore || len(third.Items) != 1 || third.Items[0].AlertID != ids[2] {
		t.Fatalf("third: %+v %v", third, err)
	}
	next, err := f.store.Changes(ctx, f.reader.Secret, ChangesQuery{Cursor: third.NextCursor, Limit: 1})
	if err != nil || next.HasMore || len(next.Items) != 1 || next.Items[0].AlertID != late.ID {
		t.Fatalf("next pass: %+v %v", next, err)
	}
}

func TestChangesPreserveMigrationFallbackAndCursorValidityAcrossRestart(t *testing.T) {
	path, sender, reader := versionOneDatabase(t, "2026-01-01T00:00:00Z")
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { store.Close() }()
	ctx := context.Background()
	checkpoint, err := store.Checkpoint(ctx, reader.Secret)
	if err != nil {
		t.Fatal(err)
	}
	alert, _, err := store.Submit(ctx, sender.Secret, []byte(strings.Replace(validAlert, "nas-1", "new", 1)))
	if err != nil {
		t.Fatal(err)
	}
	workerCtx, cancel := context.WithCancel(ctx)
	done := make(chan error, 1)
	go func() { done <- store.Run(workerCtx, nil) }()
	waitFallback(t, store, reader.Secret, "old-alert")
	waitFallback(t, store, reader.Secret, alert.ID)
	cancel()
	<-done
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	store, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	page, err := store.Changes(ctx, reader.Secret, ChangesQuery{Cursor: checkpoint.Cursor})
	if err != nil || len(page.Items) != 3 {
		t.Fatalf("migrated ledger: %+v %v", page, err)
	}
	var accepted int
	for _, item := range page.Items {
		if item.Kind == "accepted" {
			accepted++
			if item.AlertID != alert.ID || item.Revision != 0 {
				t.Fatalf("rewritten acceptance: %+v", item)
			}
		}
	}
	if accepted != 1 {
		t.Fatalf("acceptances=%d", accepted)
	}
	duplicate, created, err := store.Submit(ctx, sender.Secret, []byte(validAlert))
	if err != nil || created || duplicate.ID != "old-alert" {
		t.Fatal("migration changed duplicate identity")
	}
	empty, err := store.Changes(ctx, reader.Secret, ChangesQuery{Cursor: page.NextCursor})
	if err != nil || len(empty.Items) != 0 {
		t.Fatalf("duplicate appended changes: %+v %v", empty, err)
	}
	other := setup(t)
	_, err = other.store.Changes(ctx, other.reader.Secret, ChangesQuery{Cursor: page.NextCursor})
	category(t, err, "invalid_cursor")
	_, err = store.Changes(ctx, reader.Secret, ChangesQuery{Cursor: "x" + page.NextCursor})
	category(t, err, "invalid_cursor")
	history, err := store.History(ctx, reader.Secret, HistoryQuery{Limit: 1})
	if err != nil || history.NextCursor == nil {
		t.Fatal("missing history continuation")
	}
	_, err = store.Changes(ctx, reader.Secret, ChangesQuery{Cursor: *history.NextCursor})
	category(t, err, "invalid_cursor")
	if err := store.RevokeCredential(ctx, reader.ID); err != nil {
		t.Fatal(err)
	}
	_, err = store.Changes(ctx, reader.Secret, ChangesQuery{Cursor: page.NextCursor})
	category(t, err, "unauthorized")
}

func TestChangesDetectCheckpointBeyondRestoredLedger(t *testing.T) {
	path := filepath.Join(t.TempDir(), "rollback.sqlite")
	ctx := context.Background()
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	sender, reader := newCredential(t, store, Sender), newCredential(t, store, Installation)
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	backup, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	store, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := store.Submit(ctx, sender.Secret, []byte(validAlert)); err != nil {
		t.Fatal(err)
	}
	checkpoint, err := store.Checkpoint(ctx, reader.Secret)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, backup, 0600); err != nil {
		t.Fatal(err)
	}
	store, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	_, err = store.Changes(ctx, reader.Secret, ChangesQuery{Cursor: checkpoint.Cursor})
	category(t, err, "sync_reset_required")
}

func TestCheckpointLeavesHistorySilentAndReturnsNewAcceptance(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	if _, _, err := f.store.Submit(ctx, f.sender.Secret, []byte(validAlert)); err != nil {
		t.Fatal(err)
	}
	checkpoint, err := f.store.Checkpoint(ctx, f.reader.Secret)
	if err != nil {
		t.Fatal(err)
	}
	alert, _, err := f.store.Submit(ctx, f.sender.Secret, []byte(strings.Replace(validAlert, "nas-1", "new-event", 1)))
	if err != nil {
		t.Fatal(err)
	}
	page, err := f.store.Changes(ctx, f.reader.Secret, ChangesQuery{Cursor: checkpoint.Cursor})
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 1 || page.Items[0].AlertID != alert.ID || page.Items[0].Kind != "accepted" || page.Items[0].Revision != 0 || page.HasMore || page.NextCursor == "" {
		t.Fatalf("unexpected changes: %+v", page)
	}
	empty, err := f.store.Changes(ctx, f.reader.Secret, ChangesQuery{Cursor: page.NextCursor})
	if err != nil || len(empty.Items) != 0 || empty.HasMore || empty.NextCursor == "" {
		t.Fatalf("empty pass: %+v, %v", empty, err)
	}
	_, err = f.store.Checkpoint(ctx, f.sender.Secret)
	category(t, err, "forbidden")
	_, err = f.store.Changes(ctx, "wrong", ChangesQuery{Cursor: checkpoint.Cursor})
	category(t, err, "unauthorized")
}
