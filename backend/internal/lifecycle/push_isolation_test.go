package lifecycle

import (
	"context"
	"strings"
	"testing"
	"time"
)

func TestSlowPushDoesNotBlockReadsAcceptanceFallbackOrReplacement(t *testing.T) {
	f := setup(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	body := []byte(`{"registrationId":"11111111111111111111111111111111","provider":"fcm","destination":"PRIVATE"}`)
	if _, err := f.store.PutPush(ctx, f.reader.Secret, PushMutation{Version: 0, Key: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}, body); err != nil {
		t.Fatal(err)
	}
	alert, _, err := f.store.Submit(ctx, f.sender.Secret, []byte(validAlert))
	if err != nil {
		t.Fatal(err)
	}
	entered, release := make(chan struct{}), make(chan struct{}, 1)
	defer close(release)
	provider := pushProviderFunc(func(sendCtx context.Context, message PushMessage) PushSendResult {
		close(entered)
		select {
		case <-release:
		case <-sendCtx.Done():
		}
		return PushSendResult{Outcome: PushPermanent}
	})
	done := make(chan error, 1)
	go func() {
		_, err := f.store.NewPushDispatcher(provider, PushDispatchOptions{}).DispatchOne(ctx)
		done <- err
	}()
	select {
	case <-entered:
	case <-ctx.Done():
		t.Fatal("provider was not called")
	}
	if _, err := f.store.Read(ctx, f.reader.Secret, alert.ID); err != nil {
		t.Fatal("provider held storage during read", err)
	}
	if _, _, err := f.store.Submit(ctx, f.sender.Secret, []byte(strings.Replace(validAlert, "nas-1", "second", 1))); err != nil {
		t.Fatal("provider blocked acceptance", err)
	}
	runWorker(t, f.store)
	waitFallback(t, f.store, f.reader.Secret, alert.ID)
	refreshed, err := f.store.PutPush(ctx, f.reader.Secret, PushMutation{Version: 1, Key: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}, []byte(strings.Replace(string(body), "PRIVATE", "ROTATED", 1)))
	if err != nil {
		t.Fatal(err)
	}
	release <- struct{}{}
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("provider did not stop")
	}
	state, err := f.store.PushRegistration(context.Background(), f.reader.Secret)
	if err != nil || !state.Enabled || state.Version != refreshed.Version {
		t.Fatal("old failure disabled replacement")
	}
	recorded := &recordedPushProvider{}
	report, err := f.store.NewPushDispatcher(recorded, PushDispatchOptions{}).DispatchOne(context.Background())
	if err != nil || report.Status != "idle" || len(recorded.messages) != 0 {
		t.Fatal("old destination work survived refresh")
	}
	newest, _, err := f.store.Submit(context.Background(), f.sender.Secret, []byte(strings.Replace(validAlert, "nas-1", "newest", 1)))
	if err != nil {
		t.Fatal(err)
	}
	report, err = f.store.NewPushDispatcher(recorded, PushDispatchOptions{}).DispatchOne(context.Background())
	if err != nil || report.Status != "delivered" || len(recorded.messages) != 1 || recorded.messages[0].Destination != "ROTATED" || recorded.messages[0].Data.AlertID != newest.ID {
		t.Fatal("new destination did not receive future work")
	}
}
