package lifecycle

import (
	"context"
	"testing"
	"time"
)

func TestProviderConfigurationFailurePausesProviderWithoutDisablingPhones(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	other := newCredential(t, f.store, Installation)
	body := []byte(`{"registrationId":"11111111111111111111111111111111","provider":"fcm","destination":"PRIVATE"}`)
	for _, device := range []Credential{f.reader, other} {
		if _, err := f.store.PutPush(ctx, device.Secret, PushMutation{Version: 0, Key: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}, body); err != nil {
			t.Fatal(err)
		}
	}
	if _, _, err := f.store.Submit(ctx, f.sender.Secret, []byte(validAlert)); err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	options := PushDispatchOptions{Now: func() time.Time { return now }, RetryJitter: func(delay time.Duration) time.Duration { return delay }}
	missing, err := f.store.NewPushDispatcher(nil, options).DispatchOne(ctx)
	if err != nil || missing.Status != "not_configured" {
		t.Fatalf("missing provider: %+v %v", missing, err)
	}
	provider := &recordedPushProvider{results: []PushSendResult{{Outcome: PushConfiguration, RetryAfter: 7 * time.Minute}}}
	report, err := f.store.NewPushDispatcher(provider, options).DispatchOne(ctx)
	if err != nil || report.Status != "configuration_error" {
		t.Fatalf("configuration: %+v %v", report, err)
	}
	for _, device := range []Credential{f.reader, other} {
		state, err := f.store.PushRegistration(ctx, device.Secret)
		if err != nil || !state.Enabled || state.Version != 1 {
			t.Fatal("provider configuration invalidated a phone")
		}
	}
	now = now.Add(7*time.Minute - time.Millisecond)
	report, err = f.store.NewPushDispatcher(provider, options).DispatchOne(ctx)
	if err != nil || report.Status != "idle" || len(provider.messages) != 1 {
		t.Fatal("provider-wide cooldown was ignored")
	}
	now = now.Add(time.Millisecond)
	for i := 0; i < 2; i++ {
		report, err = f.store.NewPushDispatcher(provider, options).DispatchOne(ctx)
		if err != nil || report.Status != "delivered" {
			t.Fatalf("provider recovery: %+v %v", report, err)
		}
	}
	if len(provider.messages) != 3 {
		t.Fatal("unconfigured mode consumed an attempt or dropped work")
	}
}

func TestUnsupportedProviderWaitDoesNotRetryEarlierThanInstructed(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	if _, err := f.store.PutPush(ctx, f.reader.Secret, PushMutation{Version: 0, Key: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}, []byte(`{"registrationId":"11111111111111111111111111111111","provider":"fcm","destination":"PRIVATE"}`)); err != nil {
		t.Fatal(err)
	}
	if _, _, err := f.store.Submit(ctx, f.sender.Secret, []byte(validAlert)); err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	options := PushDispatchOptions{Now: func() time.Time { return now }}
	provider := &recordedPushProvider{results: []PushSendResult{{Outcome: PushTransient, RetryAfter: 24 * time.Hour}}}
	report, err := f.store.NewPushDispatcher(provider, options).DispatchOne(ctx)
	if err != nil || report.Status != "exhausted" {
		t.Fatalf("unsupported wait: %+v %v", report, err)
	}
	now = now.Add(48 * time.Hour)
	report, err = f.store.NewPushDispatcher(provider, options).DispatchOne(ctx)
	if err != nil || report.Status != "idle" || len(provider.messages) != 1 {
		t.Fatal("unsupported wait created an early or unbounded retry")
	}
}
