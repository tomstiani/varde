package lifecycle

import (
	"context"
	"strings"
	"testing"
)

func TestRegistrationBaselineExcludesHistoryAndSurvivesTokenRefresh(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	if _, _, err := f.store.Submit(ctx, f.sender.Secret, []byte(validAlert)); err != nil {
		t.Fatal(err)
	}
	body := []byte(`{"registrationId":"11111111111111111111111111111111","provider":"fcm","destination":"PRIVATE"}`)
	registration, err := f.store.PutPush(ctx, f.reader.Secret, PushMutation{Version: 0, Key: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}, body)
	if err != nil || !registration.Enabled || registration.Version != 1 || registration.BaselineCursor == nil {
		t.Fatalf("registration: %+v %v", registration, err)
	}
	empty, err := f.store.Changes(ctx, f.reader.Secret, ChangesQuery{Cursor: *registration.BaselineCursor})
	if err != nil || len(empty.Items) != 0 {
		t.Fatalf("history announced: %+v %v", empty, err)
	}
	arrival, _, err := f.store.Submit(ctx, f.sender.Secret, []byte(strings.Replace(validAlert, "nas-1", "later", 1)))
	if err != nil {
		t.Fatal(err)
	}
	replay, err := f.store.PutPush(ctx, f.reader.Secret, PushMutation{Version: 0, Key: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}, body)
	if err != nil || replay.Version != 1 || replay.BaselineCursor == nil || *replay.BaselineCursor != *registration.BaselineCursor {
		t.Fatalf("uncertain registration skipped an arrival: %+v %v", replay, err)
	}
	refreshed, err := f.store.PutPush(ctx, f.reader.Secret, PushMutation{Version: 1, Key: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}, []byte(strings.Replace(string(body), "PRIVATE", "ROTATED", 1)))
	if err != nil || refreshed.Version != 2 || refreshed.BaselineCursor == nil || *refreshed.BaselineCursor != *registration.BaselineCursor {
		t.Fatalf("refresh changed baseline: %+v %v", refreshed, err)
	}
	page, err := f.store.Changes(ctx, f.reader.Secret, ChangesQuery{Cursor: *refreshed.BaselineCursor})
	if err != nil || len(page.Items) != 1 || page.Items[0].AlertID != arrival.ID {
		t.Fatalf("lost arrival: %+v %v", page, err)
	}
	current, err := f.store.PushRegistration(ctx, f.reader.Secret)
	if err != nil || current.Version != 2 || current.RegistrationID == nil || *current.RegistrationID != "11111111111111111111111111111111" {
		t.Fatalf("current: %+v %v", current, err)
	}
}
