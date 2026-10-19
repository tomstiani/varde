package lifecycle

import (
	"context"
	"path/filepath"
	"testing"
)

func TestRegistrationReplayAndRetiredSessionsStayFencedAcrossRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "push.sqlite")
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { store.Close() }()
	ctx := context.Background()
	device := newCredential(t, store, Installation)
	firstBody := []byte(`{"registrationId":"11111111111111111111111111111111","provider":"fcm","destination":"PRIVATE"}`)
	firstMutation := PushMutation{Version: 0, Key: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
	first, err := store.PutPush(ctx, device.Secret, firstMutation, firstBody)
	if err != nil {
		t.Fatal(err)
	}
	secondBody := []byte(`{"registrationId":"22222222222222222222222222222222","provider":"fcm","destination":"SECOND"}`)
	second, err := store.PutPush(ctx, device.Secret, PushMutation{Version: 1, Key: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}, secondBody)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	store, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	replay, err := store.PutPush(ctx, device.Secret, firstMutation, []byte(`{ "destination":"PRIVATE", "provider":"fcm", "registrationId":"11111111111111111111111111111111" }`))
	if err != nil || replay.Version != first.Version || replay.BaselineCursor == nil || *replay.BaselineCursor != *first.BaselineCursor {
		t.Fatalf("uncertain retry: %+v %v", replay, err)
	}
	current, err := store.PushRegistration(ctx, device.Secret)
	if err != nil || current.Version != second.Version || *current.RegistrationID != *second.RegistrationID {
		t.Fatalf("replay reactivated old session: %+v %v", current, err)
	}
	_, err = store.PutPush(ctx, device.Secret, firstMutation, secondBody)
	category(t, err, "idempotency_conflict")
	_, err = store.PutPush(ctx, device.Secret, PushMutation{Version: 0, Key: "cccccccccccccccccccccccccccccccc"}, firstBody)
	category(t, err, "registration_changed")
	_, err = store.PutPush(ctx, device.Secret, PushMutation{Version: 2, Key: "dddddddddddddddddddddddddddddddd"}, firstBody)
	category(t, err, "registration_changed")
}
