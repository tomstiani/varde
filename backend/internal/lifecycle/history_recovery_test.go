package lifecycle

import (
	"context"
	"fmt"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

func TestHistoryCursorIsStoreBoundAndSurvivesRestartAndRevocation(t *testing.T) {
	ctx := context.Background()
	filename := filepath.Join(t.TempDir(), "history.sqlite")
	store, err := Open(filename)
	if err != nil {
		t.Fatal(err)
	}
	f := fixture{store: store, sender: newCredential(t, store, Sender), reader: newCredential(t, store, Installation)}
	t.Cleanup(func() { store.Close() })
	for i := 0; i < 3; i++ {
		_, _, err := f.store.Submit(ctx, f.sender.Secret, []byte(strings.Replace(validAlert, "nas-1", fmt.Sprint(i), 1)))
		if err != nil {
			t.Fatal(err)
		}
	}
	page, err := f.store.History(ctx, f.reader.Secret, HistoryQuery{Limit: 1, Order: "acceptedAt"})
	if err != nil || page.NextCursor == nil {
		t.Fatalf("first page: %+v %v", page, err)
	}
	token := *page.NextCursor
	for _, query := range []HistoryQuery{
		{Limit: 2, Order: "acceptedAt", Cursor: token},
		{Limit: 1, Order: "acceptance", Cursor: token},
		{Limit: 1, Order: "acceptedAt", Cursor: token + "x"},
		{Limit: 1, Order: "acceptedAt", Cursor: strings.Repeat("x", MaxCursorBytes+1)},
	} {
		_, err := f.store.History(ctx, f.reader.Secret, query)
		category(t, err, "invalid_cursor")
	}
	other := setup(t)
	query := HistoryQuery{Limit: 1, Order: "acceptedAt", Cursor: token}
	_, err = other.store.History(ctx, other.reader.Secret, query)
	category(t, err, "invalid_cursor")
	if err := f.store.Close(); err != nil {
		t.Fatal(err)
	}
	reopened, err := Open(filename)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	next, err := reopened.History(ctx, f.reader.Secret, query)
	if err != nil || len(next.Items) != 1 || next.Items[0].ID == page.Items[0].ID {
		t.Fatalf("resumed cursor: %+v %v", next, err)
	}
	if err := reopened.RevokeCredential(ctx, f.reader.ID); err != nil {
		t.Fatal(err)
	}
	_, err = reopened.History(ctx, f.reader.Secret, query)
	category(t, err, "unauthorized")
}

func TestHistoryConcurrentSubmissionsRemainOutsideSnapshot(t *testing.T) {
	path := filepath.Join(t.TempDir(), "shared.sqlite")
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	ctx := context.Background()
	sender := newCredential(t, store, Sender)
	reader := newCredential(t, store, Installation)
	for i := 0; i < 5; i++ {
		_, _, err := store.Submit(ctx, sender.Secret, []byte(strings.Replace(validAlert, "nas-1", fmt.Sprintf("old-%d", i), 1)))
		if err != nil {
			t.Fatal(err)
		}
	}
	first, err := store.History(ctx, reader.Secret, HistoryQuery{Limit: 2})
	if err != nil {
		t.Fatal(err)
	}
	writer, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer writer.Close()
	var writes sync.WaitGroup
	writes.Add(1)
	go func() {
		defer writes.Done()
		for i := 0; i < 5; i++ {
			_, _, err := writer.Submit(ctx, sender.Secret, []byte(strings.Replace(validAlert, "nas-1", fmt.Sprintf("new-%d", i), 1)))
			if err != nil {
				t.Error(err)
			}
		}
	}()
	seen := map[string]bool{}
	page := first
	for {
		for _, item := range page.Items {
			if seen[item.ID] {
				t.Fatal("duplicate page member")
			}
			seen[item.ID] = true
		}
		if page.NextCursor == nil {
			break
		}
		page, err = store.History(ctx, reader.Secret, HistoryQuery{Limit: 2, Cursor: *page.NextCursor})
		if err != nil {
			t.Fatal(err)
		}
	}
	writes.Wait()
	if len(seen) != 5 {
		t.Fatalf("snapshot members=%d", len(seen))
	}
	fresh, err := store.History(ctx, reader.Secret, HistoryQuery{})
	if err != nil || len(fresh.Items) != 10 {
		t.Fatalf("refresh: %d %v", len(fresh.Items), err)
	}
}
