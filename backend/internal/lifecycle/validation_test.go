package lifecycle

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
)

func TestValidTimestampAndUnicodeValuesArePreserved(t *testing.T) {
	f := setup(t)
	for i, observed := range []string{"2026-01-01t12:00:00z", "2016-12-31T23:59:60Z", "2017-01-01T00:59:60+01:00", "2026-01-01T12:00:00.123456789123Z"} {
		body := strings.Replace(validAlert, "2026-01-01T14:00:00+02:00", observed, 1)
		body = strings.Replace(body, "nas-1", fmt.Sprintf("timestamp-%d", i), 1)
		body = strings.Replace(body, "Disk pressure", `\ud83d\ude00`, 1)
		a, created, err := f.store.Submit(context.Background(), f.sender.Secret, []byte(body))
		if err != nil || !created || a.Original.ObservedAt != observed || a.Original.Title != "😀" {
			t.Fatalf("valid original rejected/rewritten: %s, %v", observed, err)
		}
		literal := strings.Replace(body, `\ud83d\ude00`, "😀", 1)
		duplicate, created, err := f.store.Submit(context.Background(), f.sender.Secret, []byte(literal))
		if err != nil || created || duplicate.ID != a.ID {
			t.Fatal("Unicode spelling changed duplicate identity")
		}
	}
}

func TestSubmissionValidation(t *testing.T) {
	f := setup(t)
	cases := []struct{ name, body, want string }{
		{"malformed", `{`, "invalid_json"},
		{"trailing", validAlert + ` {}`, "invalid_json"},
		{"duplicate keys", strings.Replace(validAlert, `"title":"Disk pressure"`, `"title":"first","title":"Disk pressure"`, 1), "invalid_json"},
		{"duplicate nested", strings.Replace(validAlert, `"data":[]`, `"data":[{"x":1,"x":2}]`, 1), "invalid_json"},
		{"nonfinite", strings.Replace(validAlert, `"schemaVersion":1`, `"schemaVersion":NaN`, 1), "invalid_json"},
		{"invalid utf8", strings.Replace(validAlert, "Disk pressure", string([]byte{255}), 1), "invalid_json"},
		{"unpaired surrogate", strings.Replace(validAlert, "Disk pressure", `\ud800`, 1), "invalid_json"},
		{"unknown", strings.Replace(validAlert, `"title":`, `"privateField":"private value","title":`, 1), "invalid_field"},
		{"version bool", strings.Replace(validAlert, `"schemaVersion":1`, `"schemaVersion":true`, 1), "invalid_field"},
		{"version unsupported", strings.Replace(validAlert, `"schemaVersion":1`, `"schemaVersion":2`, 1), "invalid_field"},
		{"version fractional", strings.Replace(validAlert, `"schemaVersion":1`, `"schemaVersion":1.0`, 1), "invalid_field"},
		{"null summary", strings.Replace(validAlert, `"summary":"Backup volume near limit"`, `"summary":null`, 1), "invalid_field"},
		{"title spaces", strings.Replace(validAlert, "Disk pressure", " \t ", 1), "invalid_json"},
		{"blank title", strings.Replace(validAlert, "Disk pressure", "   ", 1), "invalid_field"},
		{"blank identity", strings.Replace(validAlert, "nas-1", "   ", 1), "invalid_field"},
		{"title long", strings.Replace(validAlert, "Disk pressure", strings.Repeat("é", 257), 1), "invalid_field"},
		{"event long", strings.Replace(validAlert, "nas-1", strings.Repeat("x", 257), 1), "invalid_field"},
		{"summary long", strings.Replace(validAlert, "Backup volume near limit", strings.Repeat("x", 8193), 1), "invalid_field"},
		{"priority", strings.Replace(validAlert, `"priority":"high"`, `"priority":5`, 1), "invalid_field"},
		{"naive time", strings.Replace(validAlert, "2026-01-01T14:00:00+02:00", "2026-01-01T14:00:00", 1), "invalid_field"},
		{"bad offset", strings.Replace(validAlert, "+02:00", "+24:00", 1), "invalid_field"},
		{"bad date", strings.Replace(validAlert, "2026-01-01", "2026-02-30", 1), "invalid_field"},
		{"data null", strings.Replace(validAlert, `"data":[]`, `"data":null`, 1), "invalid_field"},
		{"typed data", strings.Replace(validAlert, `"data":[]`, `"data":[{"value":5}]`, 1), "invalid_field"},
		{"array root", `[]`, "invalid_field"},
		{"empty object", `{}`, "invalid_field"},
		{"too large", validAlert + strings.Repeat(" ", MaxBodyBytes), "body_too_large"},
	}
	for _, tt := range cases {
		t.Run(tt.name, func(t *testing.T) {
			_, created, err := f.store.Submit(context.Background(), f.sender.Secret, []byte(tt.body))
			if created {
				t.Fatal("invalid input accepted")
			}
			category(t, err, tt.want)
		})
	}
	for _, field := range []string{"schemaVersion", "eventId", "title", "summary", "priority", "observedAt"} {
		t.Run("missing "+field, func(t *testing.T) {
			var v map[string]any
			_ = json.Unmarshal([]byte(validAlert), &v)
			delete(v, field)
			body, _ := json.Marshal(v)
			_, _, err := f.store.Submit(context.Background(), f.sender.Secret, body)
			category(t, err, "invalid_field")
		})
	}
	var v map[string]any
	_ = json.Unmarshal([]byte(validAlert), &v)
	v["title"] = strings.Repeat("é", 256)
	v["eventId"] = strings.Repeat("i", 256)
	v["summary"] = strings.Repeat("x", 8192)
	body, _ := json.Marshal(v)
	body = append(body, []byte(strings.Repeat(" ", MaxBodyBytes-len(body)))...)
	_, created, err := f.store.Submit(context.Background(), f.sender.Secret, body)
	if err != nil || !created {
		t.Fatalf("exact limits: %v %v", created, err)
	}
}
