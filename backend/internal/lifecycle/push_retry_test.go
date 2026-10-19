package lifecycle

import (
	"context"
	"path/filepath"
	"testing"
	"time"
)

func TestPushTransientRetriesUseDurableBoundedAttemptBudget(t *testing.T) {
	path := filepath.Join(t.TempDir(), "retry.sqlite")
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { store.Close() }()
	ctx := context.Background()
	device, sender := newCredential(t, store, Installation), newCredential(t, store, Sender)
	_, err = store.PutPush(ctx, device.Secret, PushMutation{Version: 0, Key: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}, []byte(`{"registrationId":"11111111111111111111111111111111","provider":"fcm","destination":"PRIVATE"}`))
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := store.Submit(ctx, sender.Secret, []byte(validAlert)); err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	options := PushDispatchOptions{Now: func() time.Time { return now }, RetryJitter: func(delay time.Duration) time.Duration { return delay }}
	provider := &recordedPushProvider{results: []PushSendResult{
		{Outcome: PushTransient, RetryAfter: 10 * time.Second},
		{Outcome: PushTransient}, {Outcome: PushTransient}, {Outcome: PushTransient},
	}}
	for attempt, delay := range []time.Duration{10 * time.Second, 30 * time.Second, 2 * time.Minute, 0} {
		report, err := store.NewPushDispatcher(provider, options).DispatchOne(ctx)
		want := "retry_scheduled"
		if attempt == 3 {
			want = "exhausted"
		}
		if err != nil || report.Status != want || len(provider.messages) != attempt+1 {
			t.Fatalf("attempt %d: %+v %v sends=%d", attempt+1, report, err, len(provider.messages))
		}
		if err := store.Close(); err != nil {
			t.Fatal(err)
		}
		store, err = Open(path)
		if err != nil {
			t.Fatal(err)
		}
		if delay > 0 {
			now = now.Add(delay - time.Millisecond)
			report, err = store.NewPushDispatcher(provider, options).DispatchOne(ctx)
			if err != nil || report.Status != "idle" {
				t.Fatalf("early retry: %+v %v", report, err)
			}
			now = now.Add(time.Millisecond)
		}
	}
	now = now.Add(24 * time.Hour)
	report, err := store.NewPushDispatcher(provider, options).DispatchOne(ctx)
	if err != nil || report.Status != "idle" || len(provider.messages) != 4 {
		t.Fatal("retry budget reset after restart")
	}
}
