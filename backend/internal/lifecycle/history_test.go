package lifecycle

import (
	"context"
	"fmt"
	"strings"
	"testing"
	"time"
)

func TestHistoryTimestampOrderSurvivesBackwardsClockAndTies(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	var ids []string
	for i, instant := range []string{"2026-01-01T12:00:00.1Z", "2026-01-01T12:00:00Z", "2026-01-01T12:00:00.1Z"} {
		parsed, _ := time.Parse(time.RFC3339Nano, instant)
		f.store.now = func() time.Time { return parsed }
		alert, _, err := f.store.Submit(ctx, f.sender.Secret, []byte(strings.Replace(validAlert, "nas-1", fmt.Sprintf("clock-%d", i), 1)))
		if err != nil {
			t.Fatal(err)
		}
		ids = append(ids, alert.ID)
	}
	first, second := ids[0], ids[2]
	if first < second {
		first, second = second, first
	}
	var found []string
	query := HistoryQuery{Order: "acceptedAt", Limit: 1}
	for {
		page, err := f.store.History(ctx, f.reader.Secret, query)
		if err != nil {
			t.Fatal(err)
		}
		for _, item := range page.Items {
			found = append(found, item.ID)
		}
		if page.NextCursor == nil {
			break
		}
		query.Cursor = *page.NextCursor
	}
	if fmt.Sprint(found) != fmt.Sprint([]string{first, second, ids[1]}) {
		t.Fatalf("timestamp order: %v", found)
	}
}

func TestHistoryPagesRetainedOriginalsWithoutConcurrentArrivals(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	var ids []string
	for i := 0; i < 3; i++ {
		body := strings.Replace(validAlert, "nas-1", fmt.Sprintf("history-%d", i), 1)
		alert, _, err := f.store.Submit(ctx, f.sender.Secret, []byte(body))
		if err != nil {
			t.Fatal(err)
		}
		ids = append(ids, alert.ID)
	}
	page, err := f.store.History(ctx, f.reader.Secret, HistoryQuery{Limit: 2})
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 2 || page.Items[0].ID != ids[2] || page.Items[1].ID != ids[1] || page.NextCursor == nil {
		t.Fatalf("unexpected first page: %+v", page)
	}
	item := page.Items[0]
	if item.Title != "Disk pressure" || item.Priority != "high" || item.ObservedAt != "2026-01-01T14:00:00+02:00" || item.Presentation.Revision != 0 {
		t.Fatalf("summary: %+v", item)
	}
	_, _, err = f.store.Submit(ctx, f.sender.Secret, []byte(strings.Replace(validAlert, "nas-1", "new-arrival", 1)))
	if err != nil {
		t.Fatal(err)
	}
	last, err := f.store.History(ctx, f.reader.Secret, HistoryQuery{Limit: 2, Cursor: *page.NextCursor})
	if err != nil {
		t.Fatal(err)
	}
	if len(last.Items) != 1 || last.Items[0].ID != ids[0] || last.NextCursor != nil {
		t.Fatalf("snapshot continuation: %+v", last)
	}
	_, err = f.store.History(ctx, f.sender.Secret, HistoryQuery{})
	category(t, err, "forbidden")
	_, err = f.store.History(ctx, "wrong", HistoryQuery{})
	category(t, err, "unauthorized")
}
