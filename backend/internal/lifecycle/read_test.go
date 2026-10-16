package lifecycle

import (
	"context"
	"path/filepath"
	"testing"
)

func TestOriginalSurvivesReopen(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "varde.sqlite")
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	sender, err := store.CreateIdentity(ctx, Sender, "n8n")
	if err != nil {
		t.Fatal(err)
	}
	device, err := store.CreateIdentity(ctx, Installation, "phone")
	if err != nil {
		t.Fatal(err)
	}
	sendKey, err := store.IssueCredential(ctx, sender.ID)
	if err != nil {
		t.Fatal(err)
	}
	readKey, err := store.IssueCredential(ctx, device.ID)
	if err != nil {
		t.Fatal(err)
	}
	accepted, created, err := store.Submit(ctx, sendKey.Secret, []byte(validAlert))
	if err != nil || !created {
		t.Fatalf("submit: created=%v err=%v", created, err)
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	store, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	got, err := store.Read(ctx, readKey.Secret, accepted.ID)
	if err != nil {
		t.Fatal(err)
	}
	if got.ID != accepted.ID || got.Original.Title != "Disk pressure" || got.Original.ObservedAt != "2026-01-01T14:00:00+02:00" || got.Presentation.State != "pending" || got.Presentation.Revision != 0 {
		t.Fatalf("unexpected original: %+v", got)
	}
}
