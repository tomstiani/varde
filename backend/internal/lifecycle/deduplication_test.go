package lifecycle

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
)

func TestDeduplicationRotationAndFallback(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	a, created, err := f.store.Submit(ctx, f.sender.Secret, []byte(validAlert))
	if err != nil || !created {
		t.Fatalf("submit: %v %v", created, err)
	}
	var original map[string]any
	if err = json.Unmarshal([]byte(validAlert), &original); err != nil {
		t.Fatal(err)
	}
	delete(original, "data")
	formatted, _ := json.MarshalIndent(original, "", "  ")
	dup, created, err := f.store.Submit(ctx, f.sender.Secret, formatted)
	if err != nil || created || dup.ID != a.ID || dup.AcceptedAt != a.AcceptedAt {
		t.Fatalf("duplicate: %+v %v %v", dup, created, err)
	}
	_, _, err = f.store.Submit(ctx, f.sender.Secret, []byte(strings.Replace(validAlert, "Disk pressure", "Changed", 1)))
	category(t, err, "identity_conflict")
	other := newCredential(t, f.store, Sender)
	b, created, err := f.store.Submit(ctx, other.Secret, []byte(validAlert))
	if err != nil || !created || b.ID == a.ID {
		t.Fatalf("sender namespace: %v %v", created, err)
	}
	replacement, err := f.store.IssueCredential(ctx, f.sender.IdentityID)
	if err != nil {
		t.Fatal(err)
	}
	if err = f.store.RevokeCredential(ctx, f.sender.ID); err != nil {
		t.Fatal(err)
	}
	_, _, err = f.store.Submit(ctx, f.sender.Secret, []byte(validAlert))
	category(t, err, "unauthorized")
	dup, created, err = f.store.Submit(ctx, replacement.Secret, []byte(validAlert))
	if err != nil || created || dup.ID != a.ID {
		t.Fatalf("rotated duplicate: %v %v", created, err)
	}
	_, err = f.store.Read(ctx, replacement.Secret, a.ID)
	category(t, err, "forbidden")
	_, _, err = f.store.Submit(ctx, f.reader.Secret, []byte(validAlert))
	category(t, err, "forbidden")
	runWorker(t, f.store)
	terminal := waitFallback(t, f.store, f.reader.Secret, a.ID)
	before, _ := json.Marshal(a.Original)
	after, _ := json.Marshal(terminal.Original)
	if terminal.Presentation.Revision != 1 || string(before) != string(after) {
		t.Fatal("fallback changed original/revision")
	}
	dup, created, err = f.store.Submit(ctx, replacement.Secret, []byte(validAlert))
	if err != nil || created || dup.ID != a.ID || dup.Presentation.Revision != 1 {
		t.Fatalf("terminal duplicate: %+v %v %v", dup, created, err)
	}
	if err = f.store.RevokeCredential(ctx, f.reader.ID); err != nil {
		t.Fatal(err)
	}
	_, err = f.store.Read(ctx, f.reader.Secret, a.ID)
	category(t, err, "unauthorized")
}
