package lifecycle

import (
	"context"
	"strings"
	"testing"
)

func TestQueuedPushCannotOutliveRemovalRevocationOrDestinationReplacement(t *testing.T) {
	for _, mode := range []string{"remove", "revoke", "new_session", "token_refresh"} {
		t.Run(mode, func(t *testing.T) {
			f := setup(t)
			ctx := context.Background()
			body := []byte(`{"registrationId":"11111111111111111111111111111111","provider":"fcm","destination":"PRIVATE"}`)
			if _, err := f.store.PutPush(ctx, f.reader.Secret, PushMutation{Version: 0, Key: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}, body); err != nil {
				t.Fatal(err)
			}
			if _, _, err := f.store.Submit(ctx, f.sender.Secret, []byte(validAlert)); err != nil {
				t.Fatal(err)
			}
			mutation := PushMutation{Version: 1, Key: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
			var err error
			switch mode {
			case "remove":
				_, err = f.store.DeletePush(ctx, f.reader.Secret, mutation)
			case "revoke":
				err = f.store.RevokeCredential(ctx, f.reader.ID)
			case "new_session":
				_, err = f.store.PutPush(ctx, f.reader.Secret, mutation, []byte(strings.Replace(string(body), "11111111111111111111111111111111", "22222222222222222222222222222222", 1)))
			case "token_refresh":
				_, err = f.store.PutPush(ctx, f.reader.Secret, mutation, []byte(strings.Replace(string(body), "PRIVATE", "ROTATED", 1)))
			}
			if err != nil {
				t.Fatal(err)
			}
			provider := &recordedPushProvider{}
			report, err := f.store.NewPushDispatcher(provider, PushDispatchOptions{}).DispatchOne(ctx)
			if err != nil || report.Status != "idle" || len(provider.messages) != 0 {
				t.Fatal("obsolete queued work was dispatched")
			}
			if _, _, err := f.store.Submit(ctx, f.sender.Secret, []byte(strings.Replace(validAlert, "nas-1", "future", 1))); err != nil {
				t.Fatal(err)
			}
			report, err = f.store.NewPushDispatcher(provider, PushDispatchOptions{}).DispatchOne(ctx)
			want := "idle"
			if mode == "new_session" || mode == "token_refresh" {
				want = "delivered"
			}
			if err != nil || report.Status != want {
				t.Fatalf("future eligibility: %+v %v", report, err)
			}
		})
	}
}
