package lifecycle

import (
	"context"
	"testing"
)

func TestPermanentPushFailureDisablesOnlyItsDestinationAndCanBeRepaired(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	other := newCredential(t, f.store, Installation)
	body := []byte(`{"registrationId":"11111111111111111111111111111111","provider":"fcm","destination":"PRIVATE"}`)
	mutation := PushMutation{Version: 0, Key: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
	original, err := f.store.PutPush(ctx, f.reader.Secret, mutation, body)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.PutPush(ctx, other.Secret, mutation, body); err != nil {
		t.Fatal(err)
	}
	if _, _, err := f.store.Submit(ctx, f.sender.Secret, []byte(validAlert)); err != nil {
		t.Fatal(err)
	}
	provider := &recordedPushProvider{results: []PushSendResult{{Outcome: PushPermanent}, {Outcome: PushDelivered}}}
	d := f.store.NewPushDispatcher(provider, PushDispatchOptions{})
	first, err := d.DispatchOne(ctx)
	if err != nil || first.Status != "destination_disabled" {
		t.Fatalf("permanent: %+v %v", first, err)
	}
	failed, healthy := f.reader, other
	// Installation selection is deliberately unspecified; observe which destination was disabled.
	state, err := f.store.PushRegistration(ctx, failed.Secret)
	if err != nil {
		t.Fatal(err)
	}
	if state.Enabled {
		failed, healthy = healthy, failed
		state, err = f.store.PushRegistration(ctx, failed.Secret)
		if err != nil {
			t.Fatal(err)
		}
	}
	if state.Enabled || state.RegistrationID != nil || state.BaselineCursor != nil || state.Version != 2 {
		t.Fatalf("unsafe disabled state: %+v", state)
	}
	next, err := d.DispatchOne(ctx)
	if err != nil || next.Status != "delivered" || len(provider.messages) != 2 {
		t.Fatalf("one invalid token disabled other installation: %+v %v", next, err)
	}
	healthyState, err := f.store.PushRegistration(ctx, healthy.Secret)
	if err != nil || !healthyState.Enabled {
		t.Fatal("healthy token invalidated")
	}
	repaired, err := f.store.PutPush(ctx, failed.Secret, PushMutation{Version: 2, Key: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}, body)
	if err != nil || !repaired.Enabled || repaired.BaselineCursor == nil || *repaired.BaselineCursor != *original.BaselineCursor {
		t.Fatalf("repair reset session baseline: %+v %v", repaired, err)
	}
	next, err = d.DispatchOne(ctx)
	if err != nil || next.Status != "idle" {
		t.Fatal("repair backfilled old work")
	}
}
