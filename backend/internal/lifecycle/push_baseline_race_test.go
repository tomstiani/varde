package lifecycle

import (
	"context"
	"fmt"
	"path/filepath"
	"strings"
	"testing"
)

func TestRegistrationAndAcceptanceShareACommittedBaselineAcrossConnections(t *testing.T) {
	path := filepath.Join(t.TempDir(), "push.sqlite")
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	writer, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer writer.Close()
	ctx := context.Background()
	device, sender := newCredential(t, store, Installation), newCredential(t, store, Sender)
	for i := 0; i < 12; i++ {
		before, err := store.Checkpoint(ctx, device.Secret)
		if err != nil {
			t.Fatal(err)
		}
		start := make(chan struct{})
		accepted := make(chan error, 1)
		go func() {
			<-start
			_, _, err := writer.Submit(ctx, sender.Secret, []byte(strings.Replace(validAlert, "nas-1", fmt.Sprintf("arrival-%d", i), 1)))
			accepted <- err
		}()
		close(start)
		body := []byte(fmt.Sprintf(`{"registrationId":"%032x","provider":"fcm","destination":"PRIVATE"}`, i+1))
		registration, err := store.PutPush(ctx, device.Secret, PushMutation{Version: int64(i), Key: fmt.Sprintf("%032x", i+1)}, body)
		if err != nil {
			t.Fatal(err)
		}
		if err := <-accepted; err != nil {
			t.Fatal(err)
		}
		after, err := store.Checkpoint(ctx, device.Secret)
		if err != nil {
			t.Fatal(err)
		}
		baseline := *registration.BaselineCursor
		if baseline != before.Cursor && baseline != after.Cursor {
			t.Fatal("registration returned a noncommitted boundary")
		}
		page, err := store.Changes(ctx, device.Secret, ChangesQuery{Cursor: baseline})
		if err != nil {
			t.Fatal(err)
		}
		want := 0
		if baseline == before.Cursor {
			want = 1
		}
		if len(page.Items) != want {
			t.Fatalf("acceptance skipped across registration boundary: got=%d want=%d", len(page.Items), want)
		}
	}
}

func TestRemovalAndReenableKeepMutedHistorySilentAcrossRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "push.sqlite")
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { store.Close() }()
	ctx := context.Background()
	device, sender := newCredential(t, store, Installation), newCredential(t, store, Sender)
	body := []byte(`{"registrationId":"11111111111111111111111111111111","provider":"fcm","destination":"PRIVATE"}`)
	_, err = store.PutPush(ctx, device.Secret, PushMutation{Version: 0, Key: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}, body)
	if err != nil {
		t.Fatal(err)
	}
	removal := PushMutation{Version: 1, Key: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
	disabled, err := store.DeletePush(ctx, device.Secret, removal)
	if err != nil || disabled.Enabled || disabled.Version != 2 || disabled.BaselineCursor != nil {
		t.Fatalf("remove: %+v %v", disabled, err)
	}
	if _, _, err := store.Submit(ctx, sender.Secret, []byte(validAlert)); err != nil {
		t.Fatal(err)
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	store, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	_, err = store.PutPush(ctx, device.Secret, PushMutation{Version: 2, Key: "cccccccccccccccccccccccccccccccc"}, body)
	category(t, err, "registration_changed")
	fresh, err := store.PutPush(ctx, device.Secret, PushMutation{Version: 2, Key: "cccccccccccccccccccccccccccccccc"}, []byte(strings.Replace(string(body), "11111111111111111111111111111111", "22222222222222222222222222222222", 1)))
	if err != nil {
		t.Fatal(err)
	}
	page, err := store.Changes(ctx, device.Secret, ChangesQuery{Cursor: *fresh.BaselineCursor})
	if err != nil || len(page.Items) != 0 {
		t.Fatal("muted history became eligible")
	}
	replay, err := store.DeletePush(ctx, device.Secret, removal)
	if err != nil || replay.Version != 2 || replay.Enabled {
		t.Fatalf("removal replay: %+v %v", replay, err)
	}
	current, err := store.PushRegistration(ctx, device.Secret)
	if err != nil || current.Version != 3 || !current.Enabled {
		t.Fatalf("old removal disabled fresh session: %+v %v", current, err)
	}
}
