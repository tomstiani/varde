package lifecycle

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
)

func TestSubmissionExplainsValidationFailuresSafely(t *testing.T) {
	f := setup(t)
	cases := []struct {
		field   string
		value   any
		message string
	}{
		{"schemaVersion", 2, "schemaVersion must be the integer 1."},
		{"eventId", "", "eventId must be a nonblank string of at most 256 characters."},
		{"title", strings.Repeat("x", 257), "title must be a nonblank string of at most 256 characters."},
		{"summary", strings.Repeat("x", 8193), "summary must be a string of at most 8,192 characters."},
		{"priority", "PRIVATE-INPUT", "priority must be low, normal, high, or critical."},
		{"observedAt", "PRIVATE-INPUT", "observedAt must be an RFC 3339 timestamp with an explicit offset."},
		{"data", []any{"PRIVATE-INPUT"}, "This increment accepts only omitted data or an empty array."},
	}
	for _, test := range cases {
		t.Run(test.field, func(t *testing.T) {
			var original map[string]any
			if err := json.Unmarshal([]byte(validAlert), &original); err != nil {
				t.Fatal(err)
			}
			original[test.field] = test.value
			body, err := json.Marshal(original)
			if err != nil {
				t.Fatal(err)
			}
			_, _, err = f.store.Submit(context.Background(), f.sender.Secret, body)
			var failure *Failure
			if !errors.As(err, &failure) || failure.Category != "invalid_field" || failure.Field != test.field {
				t.Fatalf("unexpected validation failure: %v", err)
			}
			if failure.Message() != test.message || failure.Error() != "invalid_field" {
				t.Fatalf("unexpected safe explanation: %q", failure.Message())
			}
		})
	}
	for _, test := range []struct{ body, category, message string }{
		{`{"PRIVATE-INPUT":true}`, "invalid_field", "The fields must match the version 1 alert schema."},
		{`{"PRIVATE-INPUT":`, "invalid_json", "Supply one valid UTF-8 JSON value without duplicate object keys."},
		{strings.Repeat(" ", MaxBodyBytes+1), "body_too_large", "The JSON body must not exceed 65,536 bytes."},
	} {
		_, _, err := f.store.Submit(context.Background(), f.sender.Secret, []byte(test.body))
		var failure *Failure
		if !errors.As(err, &failure) || failure.Category != test.category || failure.Field != "" || failure.Message() != test.message {
			t.Fatalf("unexpected general validation failure: %v", err)
		}
	}
}
