package lifecycle

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestMetricSelectionKeepsConflictingSourcesSeparateAndOrdersBandsStably(t *testing.T) {
	f := setup(t)
	accepted, _, err := f.store.Submit(context.Background(), f.sender.Secret, metricBody(`[{"type":"measurement","label":"CPU","value":83,"source":"monitor-a"},{"type":"measurement","label":"CPU","value":83,"source":"monitor-b"}]`))
	if err != nil {
		t.Fatal(err)
	}
	_, err = f.store.completeNextSelection(context.Background(), func(candidates []Candidate) map[string]string {
		if len(candidates) != 3 || candidates[0].Source == nil || *candidates[0].Source != "monitor-a" || candidates[1].Source == nil || *candidates[1].Source != "monitor-b" {
			t.Fatalf("merged sources: %+v", candidates)
		}
		for _, candidate := range candidates[:2] {
			if candidate.Unit != nil || candidate.MeasuredAt != nil || candidate.Resource != nil || candidate.Collector != nil {
				t.Fatalf("invented metadata: %+v", candidate)
			}
		}
		return map[string]string{"d0.metric": "p1", "d1.metric": "p0", "summary": "p0"}
	})
	if err != nil {
		t.Fatal(err)
	}
	got, err := f.store.Read(context.Background(), f.reader.Secret, accepted.ID)
	encoded, _ := json.Marshal(got.RichView)
	if err != nil || string(encoded) != `{"schemaVersion":1,"components":[{"type":"metric","dataIndex":1,"reading":"value"},{"type":"text","field":"summary"},{"type":"metric","dataIndex":0,"reading":"value"}]}` {
		t.Fatalf("%s %v", encoded, err)
	}
	if !strings.Contains(string(got.Original.Data[0]), `monitor-a`) || !strings.Contains(string(got.Original.Data[1]), `monitor-b`) {
		t.Fatal("originals reordered")
	}
}

func TestApprovedStandaloneSelectionFixtures(t *testing.T) {
	raw, err := os.ReadFile("../../../docs/specs/rich-alert-contracts/fixtures.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixtures struct {
		Base  map[string]json.RawMessage `json:"baseOriginal"`
		Cases []struct {
			ID      string                     `json:"id"`
			Input   map[string]json.RawMessage `json:"input"`
			Answers map[string]string          `json:"answers"`
			Expect  struct {
				Outcome    string      `json:"outcome"`
				Components []Component `json:"components"`
			} `json:"expect"`
		} `json:"cases"`
	}
	if err = json.Unmarshal(raw, &fixtures); err != nil {
		t.Fatal(err)
	}
	supported := map[string]bool{"minimal-measurement": true, "source-default-cleared": true, "whole-summary-and-stable-position-tie": true, "all-omit-fallback": true, "invalid-option-whole-fallback": true}
	for _, fixture := range fixtures.Cases {
		if !supported[fixture.ID] {
			continue
		}
		t.Run(fixture.ID, func(t *testing.T) {
			f := setup(t)
			original := map[string]json.RawMessage{}
			for key, value := range fixtures.Base {
				original[key] = value
			}
			for key, value := range fixture.Input {
				original[key] = value
			}
			body, _ := json.Marshal(original)
			accepted, _, err := f.store.Submit(context.Background(), f.sender.Secret, body)
			if err != nil {
				t.Fatal(err)
			}
			_, err = f.store.completeNextSelection(context.Background(), func([]Candidate) map[string]string { return fixture.Answers })
			if err != nil {
				t.Fatal(err)
			}
			got, err := f.store.Read(context.Background(), f.reader.Secret, accepted.ID)
			if err != nil {
				t.Fatal(err)
			}
			if fixture.Expect.Outcome != "rich" {
				if got.RichView != nil || got.Presentation.State != "fallback" {
					t.Fatalf("%+v", got)
				}
				return
			}
			expected, _ := json.Marshal(&RichView{SchemaVersion: 1, Components: fixture.Expect.Components})
			actual, _ := json.Marshal(got.RichView)
			if got.Presentation.State != "rich" || string(actual) != string(expected) {
				t.Fatalf("%s want %s", actual, expected)
			}
		})
	}
}

func TestSuppliedMetricPublishesWholeViewAndSurvivesRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "varde.sqlite")
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { store.Close() })
	sender := newCredential(t, store, Sender)
	reader := newCredential(t, store, Installation)
	body := []byte(strings.Replace(string(metricBody(`[{"type":"measurement","label":"CPU usage","value":83.00,"unit":"%","source":null}]`)), `"data":`, `"dataDefaults":{"source":"monitor","resource":"host-a","collector":"agent-a"},"data":`, 1))
	accepted, _, err := store.Submit(context.Background(), sender.Secret, body)
	if err != nil {
		t.Fatal(err)
	}
	store.Close()
	store, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	// Selection is injected only here, never through a sender, configuration or production mode.
	completed, err := store.completeNextSelection(context.Background(), func(candidates []Candidate) map[string]string {
		encoded, _ := json.Marshal(candidates)
		if !strings.Contains(string(encoded), `"value":"83.00"`) || !strings.Contains(string(encoded), `"source":null`) || !strings.Contains(string(encoded), `"resource":"host-a"`) {
			t.Fatalf("associations: %s", encoded)
		}
		return map[string]string{"d0.metric": "p1", "summary": "p1"}
	})
	if err != nil || !completed {
		t.Fatalf("publish: %v %v", completed, err)
	}
	got, err := store.Read(context.Background(), reader.Secret, accepted.ID)
	encoded, _ := json.Marshal(got.RichView)
	if err != nil || got.Presentation != (Presentation{State: "rich", Revision: 1}) || string(encoded) != `{"schemaVersion":1,"components":[{"type":"metric","dataIndex":0,"reading":"value"},{"type":"text","field":"summary"}]}` {
		t.Fatalf("read: %+v %s %v", got, encoded, err)
	}
	page, err := store.History(context.Background(), reader.Secret, HistoryQuery{})
	if err != nil || len(page.Items) != 1 || page.Items[0].Presentation.State != "rich" {
		t.Fatalf("history: %+v %v", page, err)
	}
	store.Close()
	store, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	restored, err := store.Read(context.Background(), reader.Secret, accepted.ID)
	if err != nil || restored.RichView == nil || string(restored.Original.Data[0]) != string(got.Original.Data[0]) {
		t.Fatalf("restart: %+v %v", restored, err)
	}
	duplicate, created, err := store.Submit(context.Background(), sender.Secret, body)
	if err != nil || created || duplicate.Presentation.State != "rich" {
		t.Fatalf("duplicate: %+v %v", duplicate, err)
	}
}
