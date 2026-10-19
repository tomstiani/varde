package lifecycle

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

type pushProviderFunc func(context.Context, PushMessage) PushSendResult

func (f pushProviderFunc) Send(ctx context.Context, message PushMessage) PushSendResult {
	return f(ctx, message)
}

func TestPushDispatchCrashHelper(t *testing.T) {
	if os.Getenv("VARDE_TEST_PUSH_CRASH") != "1" {
		return
	}
	store, err := Open(os.Getenv("VARDE_TEST_DATABASE"))
	if err != nil {
		t.Fatal(err)
	}
	provider := pushProviderFunc(func(context.Context, PushMessage) PushSendResult {
		// Simulate external success followed by process loss before completion is recorded.
		os.Exit(29)
		return PushSendResult{}
	})
	_, err = store.NewPushDispatcher(provider, PushDispatchOptions{
		Now:         func() time.Time { return time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC) },
		RetryJitter: func(delay time.Duration) time.Duration { return delay },
	}).DispatchOne(context.Background())
	t.Fatal("helper did not dispatch", err)
}

func TestUncertainPushAttemptCountsAcrossProcessDeath(t *testing.T) {
	path := filepath.Join(t.TempDir(), "crash.sqlite")
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	device, sender := newCredential(t, store, Installation), newCredential(t, store, Sender)
	if _, err := store.PutPush(ctx, device.Secret, PushMutation{Version: 0, Key: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}, []byte(`{"registrationId":"11111111111111111111111111111111","provider":"fcm","destination":"PRIVATE"}`)); err != nil {
		t.Fatal(err)
	}
	if _, _, err := store.Submit(ctx, sender.Secret, []byte(validAlert)); err != nil {
		t.Fatal(err)
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	command := exec.Command(os.Args[0], "-test.run=^TestPushDispatchCrashHelper$")
	command.Env = append(os.Environ(), "VARDE_TEST_PUSH_CRASH=1", "VARDE_TEST_DATABASE="+path)
	output, err := command.CombinedOutput()
	exit, ok := err.(*exec.ExitError)
	if !ok || exit.ExitCode() != 29 || len(output) != 0 {
		t.Fatalf("unexpected child outcome: %v", err)
	}
	store, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	options := PushDispatchOptions{Now: func() time.Time { return now }, RetryJitter: func(delay time.Duration) time.Duration { return delay }}
	provider := &recordedPushProvider{results: []PushSendResult{{Outcome: PushTransient}, {Outcome: PushTransient}, {Outcome: PushTransient}}}
	report, err := store.NewPushDispatcher(provider, options).DispatchOne(ctx)
	if err != nil || report.Status != "idle" {
		t.Fatal("uncertain reservation retried before its deadline")
	}
	now = now.Add(15 * time.Second)
	for attempt, delay := range []time.Duration{30 * time.Second, 2 * time.Minute, 24 * time.Hour} {
		report, err = store.NewPushDispatcher(provider, options).DispatchOne(ctx)
		want := "retry_scheduled"
		if attempt == 2 {
			want = "exhausted"
		}
		if err != nil || report.Status != want {
			t.Fatalf("post-crash attempt: %+v %v", report, err)
		}
		now = now.Add(delay)
	}
	report, err = store.NewPushDispatcher(provider, options).DispatchOne(ctx)
	if err != nil || report.Status != "idle" || len(provider.messages) != 3 {
		t.Fatal("crash reset the four-attempt budget")
	}
}
