package lifecycle

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"
	"testing"
)

func TestMetricSelectionFallsBackAtomically(t *testing.T) {
	for _, test := range []struct {
		name     string
		answers  map[string]string
		category string
	}{
		{"all omit", map[string]string{"d0.metric": "omit", "summary": "omit"}, "empty_selection"},
		{"missing", map[string]string{"d0.metric": "p0"}, "invalid_output"},
		{"unknown", map[string]string{"d0.metric": "p0", "other": "p1"}, "invalid_output"},
		{"bad option", map[string]string{"d0.metric": "p0", "summary": "p99"}, "invalid_output"},
	} {
		t.Run(test.name, func(t *testing.T) {
			f := setup(t)
			accepted, _, err := f.store.Submit(context.Background(), f.sender.Secret, metricBody(`[{"type":"measurement","label":"CPU","value":83}]`))
			if err != nil {
				t.Fatal(err)
			}
			_, err = f.store.completeNextSelection(context.Background(), func([]Candidate) map[string]string { return test.answers })
			if err != nil {
				t.Fatal(err)
			}
			got, err := f.store.Read(context.Background(), f.reader.Secret, accepted.ID)
			if err != nil || got.RichView != nil || got.Presentation != (Presentation{State: "fallback", Revision: 1}) || got.Original.Summary != "Backup volume near limit" {
				t.Fatalf("fallback: %+v %v", got, err)
			}
			// Categories are private diagnostics, not part of public original/read data.
			var category string
			if err = f.store.db.QueryRow("SELECT category FROM alerts WHERE id=?", accepted.ID).Scan(&category); err != nil || category != test.category {
				t.Fatalf("category %s %v", category, err)
			}
			called := false
			completed, err := f.store.completeNextSelection(context.Background(), func([]Candidate) map[string]string { called = true; return nil })
			if err != nil || completed || called {
				t.Fatalf("terminal work repeated: %v %v %v", completed, called, err)
			}
		})
	}
}

func TestUnreadableStoredRichViewFallsBackAsAWhole(t *testing.T) {
	for _, view := range []string{
		`{"schemaVersion":2,"components":[{"type":"metric","dataIndex":0,"reading":"value"}]}`,
		`{"schemaVersion":1,"components":[{"type":"metric","dataIndex":0,"reading":"value"},{"type":"unknown"}]}`,
		`{"schemaVersion":1,"components":[{"type":"metric","dataIndex":1,"reading":"value"}]}`,
		`{"schemaVersion":1,"components":[{"type":"metric","dataIndex":0,"reading":"value","style":"red"}]}`,
		`{"schemaVersion":1,"components":[{"type":"metric","dataIndex":0,"reading":"value"},{"type":"metric","dataIndex":0,"reading":"value"}]}`,
		`{"schemaVersion":1,"schemaVersion":1,"components":[{"type":"text","field":"summary"}]}`,
	} {
		f := setup(t)
		accepted, _, err := f.store.Submit(context.Background(), f.sender.Secret, metricBody(`[{"type":"measurement","label":"CPU","value":83}]`))
		if err != nil {
			t.Fatal(err)
		}
		_, err = f.store.completeNextSelection(context.Background(), func([]Candidate) map[string]string { return map[string]string{"d0.metric": "p0", "summary": "p1"} })
		if err != nil {
			t.Fatal(err)
		}
		// Corruption injection exercises read validation independently of the trusted assembler.
		if _, err = f.store.db.Exec("UPDATE alerts SET rich_view=? WHERE id=?", []byte(view), accepted.ID); err != nil {
			t.Fatal(err)
		}
		got, err := f.store.Read(context.Background(), f.reader.Secret, accepted.ID)
		if err != nil || got.RichView != nil || got.Presentation.State != "fallback" || got.Original.Title != "Disk pressure" {
			t.Fatalf("partial view: %+v %v", got, err)
		}
	}
}

func TestMetricSelectionOverflowIsNotClipped(t *testing.T) {
	f := setup(t)
	entries := make([]string, 8)
	answers := map[string]string{"summary": "p0"}
	for index := range entries {
		entries[index] = `{"type":"measurement","label":"CPU","value":83}`
		answers["d"+string(rune('0'+index))+".metric"] = "p0"
	}
	accepted, _, err := f.store.Submit(context.Background(), f.sender.Secret, metricBody("["+strings.Join(entries, ",")+"]"))
	if err != nil {
		t.Fatal(err)
	}
	_, err = f.store.completeNextSelection(context.Background(), func([]Candidate) map[string]string { return answers })
	if err != nil {
		t.Fatal(err)
	}
	got, err := f.store.Read(context.Background(), f.reader.Secret, accepted.ID)
	if err != nil || got.RichView != nil || got.Presentation.State != "fallback" {
		t.Fatalf("partial publication: %+v %v", got, err)
	}
}

func TestMetricPublicationRollbackLeavesPendingOriginal(t *testing.T) {
	f := setup(t)
	accepted, _, err := f.store.Submit(context.Background(), f.sender.Secret, metricBody(`[{"type":"measurement","label":"CPU","value":83}]`))
	if err != nil {
		t.Fatal(err)
	}
	f.store.beforeWorkerCommit = func(*sql.Conn) error { return errors.New("test failure") }
	_, err = f.store.completeNextSelection(context.Background(), func([]Candidate) map[string]string { return map[string]string{"d0.metric": "p0", "summary": "p0"} })
	if err == nil {
		t.Fatal("expected failed commit")
	}
	got, err := f.store.Read(context.Background(), f.reader.Secret, accepted.ID)
	if err != nil || got.RichView != nil || got.Presentation != (Presentation{State: "pending", Revision: 0}) {
		t.Fatalf("partial commit: %+v %v", got, err)
	}
}

func TestNoUsefulMetricNeverInvokesSelection(t *testing.T) {
	for _, data := range []string{`[]`, `[{"type":"measurement","label":"CPU","value":null}]`} {
		f := setup(t)
		accepted, _, err := f.store.Submit(context.Background(), f.sender.Secret, metricBody(data))
		if err != nil {
			t.Fatal(err)
		}
		_, err = f.store.completeNextSelection(context.Background(), func([]Candidate) map[string]string { t.Fatal("selected a summary-only catalog"); return nil })
		if err != nil {
			t.Fatal(err)
		}
		got, err := f.store.Read(context.Background(), f.reader.Secret, accepted.ID)
		if err != nil || got.RichView != nil || got.Presentation.State != "fallback" {
			t.Fatalf("%+v %v", got, err)
		}
	}
}

func TestMetricNoModelOperationAndNullBypass(t *testing.T) {
	for _, value := range []string{"83", "null"} {
		f := setup(t)
		accepted, _, err := f.store.Submit(context.Background(), f.sender.Secret, metricBody(`[{"type":"measurement","label":"CPU","value":`+value+`}]`))
		if err != nil {
			t.Fatal(err)
		}
		runWorker(t, f.store)
		got := waitFallback(t, f.store, f.reader.Secret, accepted.ID)
		encoded, _ := json.Marshal(got)
		if got.RichView != nil || !strings.Contains(string(encoded), `"value":`+value) {
			t.Fatalf("changed original %s", encoded)
		}
	}
}
