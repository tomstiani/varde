package lifecycle

import (
	"context"
	"fmt"
	"strings"
	"testing"
	"time"
)

func TestPushWorkerBoundsConcurrentCallsAndStopsOnCancellation(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	if _, err := f.store.PutPush(ctx, f.reader.Secret, PushMutation{Version: 0, Key: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}, []byte(`{"registrationId":"11111111111111111111111111111111","provider":"fcm","destination":"PRIVATE"}`)); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 5; i++ {
		if _, _, err := f.store.Submit(ctx, f.sender.Secret, []byte(strings.Replace(validAlert, "nas-1", fmt.Sprint(i), 1))); err != nil {
			t.Fatal(err)
		}
	}
	entered := make(chan struct{}, 8)
	provider := pushProviderFunc(func(sendCtx context.Context, _ PushMessage) PushSendResult {
		entered <- struct{}{}
		<-sendCtx.Done()
		return PushSendResult{Outcome: PushTransient}
	})
	workerCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	done := make(chan error, 1)
	go func() {
		done <- f.store.NewPushDispatcher(provider, PushDispatchOptions{Timeout: time.Second}).Run(workerCtx)
	}()
	for i := 0; i < 4; i++ {
		select {
		case <-entered:
		case <-time.After(3 * time.Second):
			t.Fatal("healthy work was starved by a slow provider")
		}
	}
	select {
	case <-entered:
		t.Fatal("more than four provider calls ran concurrently")
	case <-time.After(30 * time.Millisecond):
	}
	cancel()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("push worker did not stop")
	}
}

func TestPushProviderReceivesFiniteDeadline(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	if _, err := f.store.PutPush(ctx, f.reader.Secret, PushMutation{Version: 0, Key: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}, []byte(`{"registrationId":"11111111111111111111111111111111","provider":"fcm","destination":"PRIVATE"}`)); err != nil {
		t.Fatal(err)
	}
	if _, _, err := f.store.Submit(ctx, f.sender.Secret, []byte(validAlert)); err != nil {
		t.Fatal(err)
	}
	provider := pushProviderFunc(func(sendCtx context.Context, _ PushMessage) PushSendResult {
		<-sendCtx.Done()
		return PushSendResult{Outcome: PushTransient}
	})
	start := time.Now()
	report, err := f.store.NewPushDispatcher(provider, PushDispatchOptions{Timeout: 20 * time.Millisecond}).DispatchOne(ctx)
	if err != nil || report.Status != "retry_scheduled" || time.Since(start) > time.Second {
		t.Fatalf("provider deadline: %+v %v", report, err)
	}
}
