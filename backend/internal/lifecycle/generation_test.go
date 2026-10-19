package lifecycle

import (
	"context"
	"database/sql"
	"path/filepath"
	"testing"
)

func TestGenerationMigrationPreservesVersionFourOriginalsAndPendingWork(t *testing.T) {
	path := filepath.Join(t.TempDir(), "version-four.sqlite")
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	sender, reader := newCredential(t, store, Sender), newCredential(t, store, Installation)
	accepted, _, err := store.Submit(context.Background(), sender.Secret, []byte(validAlert))
	if err != nil {
		t.Fatal(err)
	}
	store.Close()
	old, err := sql.Open("sqlite", databaseURL(path))
	if err != nil {
		t.Fatal(err)
	}
	// Restore the delivered version-4 layout without changing retained alert work.
	_, err = old.Exec(`DROP TABLE generation_activation; DROP TABLE generation_manifests; PRAGMA user_version=4;`)
	old.Close()
	if err != nil {
		t.Fatal(err)
	}
	store, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { store.Close() })
	manifest := GenerationManifest{ConfigID: "migration-test", AccountRef: "synthetic", Model: "jev-1.13.0", PromptRevision: "candidate-choice-1", ContractVersion: 1, CandidateVersion: 1}
	if category, err := store.ConfigureGeneration(context.Background(), &manifest); err != nil || category != "generation_ready" {
		t.Fatalf("migrated activation: %s %v", category, err)
	}
	duplicate, created, err := store.Submit(context.Background(), sender.Secret, []byte(validAlert))
	if err != nil || created || duplicate.ID != accepted.ID {
		t.Fatal("migration changed duplicate identity")
	}
	runWorker(t, store)
	alert := waitFallback(t, store, reader.Secret, accepted.ID)
	if alert.Original.Summary != "Backup volume near limit" || alert.Presentation.Revision != 1 {
		t.Fatal("migration changed original fallback")
	}
}

func TestGenerationActivationRetainsSemanticIdentityAcrossRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "activation.sqlite")
	manifest := GenerationManifest{ConfigID: "revision-1", AccountRef: "synthetic", Model: "jev-1.13.0", PromptRevision: "candidate-choice-1", ContractVersion: 1, CandidateVersion: 1}
	activate := func(want string, selected *GenerationManifest) {
		t.Helper()
		store, err := Open(path)
		if err != nil {
			t.Fatal(err)
		}
		defer store.Close()
		got, err := store.ConfigureGeneration(context.Background(), selected)
		if err != nil || got != want {
			t.Fatalf("activation: %s %v", got, err)
		}
	}
	activate("generation_ready", &manifest)
	activate("generation_ready", &manifest)
	for _, change := range []func(*GenerationManifest){
		func(m *GenerationManifest) { m.Model = "different-model" },
		func(m *GenerationManifest) { m.PromptRevision = "candidate-choice-2" },
		func(m *GenerationManifest) { m.ContractVersion = 2 },
		func(m *GenerationManifest) { m.CandidateVersion = 2 },
	} {
		changed := manifest
		change(&changed)
		activate("generation_configuration_invalid", &changed)
	}
	activate("generation_disabled", nil)
	changed := manifest
	changed.AccountRef = "different-project"
	activate("generation_configuration_invalid", &changed)
	activate("generation_ready", &manifest)
	changed.ConfigID = "revision-2"
	activate("generation_ready", &changed)
	activate("generation_configuration_invalid", &GenerationManifest{ConfigID: "revision-2", AccountRef: "synthetic", Model: "jev-1.13.0", PromptRevision: "candidate-choice-1", ContractVersion: 1, CandidateVersion: 1})
}
