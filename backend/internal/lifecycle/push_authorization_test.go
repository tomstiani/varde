package lifecycle

import (
	"context"
	"testing"
)

func TestPushRegistrationIsInstallationScopedAndRevocationAppliesToReplay(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	other := newCredential(t, f.store, Installation)
	body := []byte(`{"registrationId":"11111111111111111111111111111111","provider":"fcm","destination":"PRIVATE"}`)
	mutation := PushMutation{Version: 0, Key: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
	first, err := f.store.PutPush(ctx, f.reader.Secret, mutation, body)
	if err != nil {
		t.Fatal(err)
	}
	isolated, err := f.store.PushRegistration(ctx, other.Secret)
	if err != nil || isolated.Enabled || isolated.Version != 0 {
		t.Fatal("installation saw another destination")
	}
	second, err := f.store.PutPush(ctx, other.Secret, mutation, body)
	if err != nil || second.Version != 1 {
		t.Fatalf("installation replay namespaces collided: %+v %v", second, err)
	}
	for _, test := range []struct{ token, category string }{{f.sender.Secret, "forbidden"}, {"invalid", "unauthorized"}} {
		_, err := f.store.PushRegistration(ctx, test.token)
		category(t, err, test.category)
		_, err = f.store.PutPush(ctx, test.token, mutation, body)
		category(t, err, test.category)
		_, err = f.store.DeletePush(ctx, test.token, PushMutation{Version: 1, Key: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"})
		category(t, err, test.category)
	}
	if err := f.store.RevokeCredential(ctx, f.reader.ID); err != nil {
		t.Fatal(err)
	}
	_, err = f.store.PutPush(ctx, f.reader.Secret, mutation, body)
	category(t, err, "unauthorized")
	_, err = f.store.PushRegistration(ctx, f.reader.Secret)
	category(t, err, "unauthorized")
	_, err = f.store.DeletePush(ctx, f.reader.Secret, PushMutation{Version: 1, Key: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"})
	category(t, err, "unauthorized")
	rotated, err := f.store.IssueCredential(ctx, f.reader.IdentityID)
	if err != nil {
		t.Fatal(err)
	}
	current, err := f.store.PushRegistration(ctx, rotated.Secret)
	if err != nil || current.Version != first.Version || *current.BaselineCursor != *first.BaselineCursor {
		t.Fatalf("credential rotation lost session: %+v %v", current, err)
	}
}
