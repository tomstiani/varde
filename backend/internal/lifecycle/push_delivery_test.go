package lifecycle

import (
	"context"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type recordedPushProvider struct {
	messages []PushMessage
	results  []PushSendResult
}

func (p *recordedPushProvider) Send(_ context.Context, message PushMessage) PushSendResult {
	p.messages = append(p.messages, message)
	if len(p.results) == 0 {
		return PushSendResult{Outcome: PushDelivered}
	}
	result := p.results[0]
	p.results = p.results[1:]
	return result
}

func TestAcceptedAlertQueuesOnlyOneFuturePushAcrossRestartAndFallback(t *testing.T) {
	path := filepath.Join(t.TempDir(), "push.sqlite")
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { store.Close() }()
	ctx := context.Background()
	sender, device := newCredential(t, store, Sender), newCredential(t, store, Installation)
	if _, _, err := store.Submit(ctx, sender.Secret, []byte(validAlert)); err != nil {
		t.Fatal(err)
	}
	registrationID := "11111111111111111111111111111111"
	_, err = store.PutPush(ctx, device.Secret, PushMutation{Version: 0, Key: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}, []byte(`{"registrationId":"`+registrationID+`","provider":"fcm","destination":"PRIVATE"}`))
	if err != nil {
		t.Fatal(err)
	}
	futureBody := []byte(strings.Replace(validAlert, "nas-1", "future", 1))
	future, _, err := store.Submit(ctx, sender.Secret, futureBody)
	if err != nil {
		t.Fatal(err)
	}
	if _, created, err := store.Submit(ctx, sender.Secret, futureBody); err != nil || created {
		t.Fatal("sender retry changed acceptance")
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	store, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	provider := &recordedPushProvider{}
	dispatcher := store.NewPushDispatcher(provider, PushDispatchOptions{})
	report, err := dispatcher.DispatchOne(ctx)
	if err != nil || report.Status != "delivered" || len(provider.messages) != 1 {
		t.Fatalf("dispatch: %+v %v sends=%d", report, err, len(provider.messages))
	}
	message := provider.messages[0]
	if message.Provider != "fcm" || message.Destination != "PRIVATE" || message.Data != (PushEnvelope{Version: "1", RegistrationID: registrationID, AlertID: future.ID}) {
		t.Fatal("incorrect private routing envelope")
	}
	workerCtx, cancel := context.WithCancel(ctx)
	done := make(chan error, 1)
	go func() { done <- store.Run(workerCtx, nil) }()
	waitFallback(t, store, device.Secret, future.ID)
	cancel()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("fallback worker did not stop")
	}
	report, err = dispatcher.DispatchOne(ctx)
	if err != nil || report.Status != "idle" || len(provider.messages) != 1 {
		t.Fatalf("history, duplicate or fallback queued another push: %+v %v", report, err)
	}
}
