package httpapi

import (
	"context"
	"encoding/json"
	"net/http/httptest"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func TestValidationFailureResponsesRemainCompatible(t *testing.T) {
	store, err := lifecycle.Open(filepath.Join(t.TempDir(), "varde.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	identity, err := store.CreateIdentity(context.Background(), lifecycle.Sender, "test")
	if err != nil {
		t.Fatal(err)
	}
	credential, err := store.IssueCredential(context.Background(), identity.ID)
	if err != nil {
		t.Fatal(err)
	}
	handler := New(store)

	cases := []struct {
		name, body, field, category, message string
		status                               int
	}{
		{"version", strings.Replace(payload, `"schemaVersion":1`, `"schemaVersion":true`, 1), "schemaVersion", "invalid_field", "schemaVersion must be the integer 1.", 422},
		{"event ID", strings.Replace(payload, "event-http", "", 1), "eventId", "invalid_field", "eventId must be a nonblank string of at most 256 characters.", 422},
		{"title", strings.Replace(payload, "PRIVATE-TITLE", strings.Repeat("x", 257), 1), "title", "invalid_field", "title must be a nonblank string of at most 256 characters.", 422},
		{"summary", strings.Replace(payload, "PRIVATE-BODY", strings.Repeat("x", 8193), 1), "summary", "invalid_field", "summary must be a string of at most 8,192 characters.", 422},
		{"priority", strings.Replace(payload, `"high"`, `"PRIVATE-VALUE"`, 1), "priority", "invalid_field", "priority must be low, normal, high, or critical.", 422},
		{"timestamp", strings.Replace(payload, "2026-01-01T12:00:00Z", "PRIVATE-VALUE", 1), "observedAt", "invalid_field", "observedAt must be an RFC 3339 timestamp with an explicit offset.", 422},
		{"data", strings.TrimSuffix(payload, "}") + `,"data":["PRIVATE-VALUE"]}`, "data", "invalid_field", "This increment accepts only omitted data or an empty array.", 422},
		{"unknown field", strings.TrimSuffix(payload, "}") + `,"PRIVATE-FIELD":"PRIVATE-VALUE"}`, "", "invalid_field", "The fields must match the version 1 alert schema.", 422},
		{"malformed", `{"PRIVATE-FIELD":`, "", "invalid_json", "Supply one valid UTF-8 JSON value without duplicate object keys.", 400},
		{"oversized", strings.Repeat(" ", lifecycle.MaxBodyBytes+1), "", "body_too_large", "The JSON body must not exceed 65,536 bytes.", 413},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			request := httptest.NewRequest("POST", "/v1/alerts", strings.NewReader(test.body))
			request.Header.Set("Authorization", "Bearer "+credential.Secret)
			request.Header.Set("Content-Type", "application/json")
			response := httptest.NewRecorder()
			handler.ServeHTTP(response, request)
			want := map[string]any{"error": test.category, "message": test.message}
			if test.field != "" {
				want["field"] = test.field
			}
			var got map[string]any
			if err := json.Unmarshal(response.Body.Bytes(), &got); err != nil {
				t.Fatal(err)
			}
			if response.Code != test.status || !reflect.DeepEqual(got, want) {
				t.Fatalf("response changed: status=%d body=%s", response.Code, response.Body.String())
			}
		})
	}
}
