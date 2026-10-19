package httpapi

import (
	"context"
	"encoding/json"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func TestHTTPPushConditionalMutationsAndReplay(t *testing.T) {
	store, err := lifecycle.Open(filepath.Join(t.TempDir(), "push.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	identity, err := store.CreateIdentity(context.Background(), lifecycle.Installation, "test")
	if err != nil {
		t.Fatal(err)
	}
	device, err := store.IssueCredential(context.Background(), identity.ID)
	if err != nil {
		t.Fatal(err)
	}
	h := New(store)
	request := func(method, etag, key, body string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, "/v1/installation/push", strings.NewReader(body))
		r.Header.Set("Authorization", "Bearer "+device.Secret)
		r.Header.Set("Content-Type", "application/json")
		if etag != "" {
			r.Header.Set("If-Match", etag)
		}
		if key != "" {
			r.Header.Set("Idempotency-Key", key)
		}
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w
	}
	body := `{"registrationId":"11111111111111111111111111111111","provider":"fcm","destination":"PRIVATE"}`
	key := "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	first := request("PUT", `"0"`, key, body)
	if first.Code != 200 || first.Header().Get("ETag") != `"1"` || strings.Contains(first.Body.String(), "PRIVATE") {
		t.Fatalf("put: %d %s %s", first.Code, first.Header().Get("ETag"), first.Body)
	}
	var registration lifecycle.PushRegistration
	if err := json.Unmarshal(first.Body.Bytes(), &registration); err != nil || !registration.Enabled || registration.BaselineCursor == nil {
		t.Fatal("missing registration baseline")
	}
	deleted := request("DELETE", `"1"`, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "")
	if deleted.Code != 200 || deleted.Header().Get("ETag") != `"2"` || strings.TrimSpace(deleted.Body.String()) != `{"registrationId":null,"provider":null,"enabled":false,"baselineCursor":null}` {
		t.Fatalf("delete: %d %s", deleted.Code, deleted.Body)
	}
	replay := request("PUT", `"0"`, key, body)
	if replay.Code != 200 || replay.Header().Get("ETag") != `"1"` || replay.Body.String() != first.Body.String() {
		t.Fatalf("replay: %d %s", replay.Code, replay.Body)
	}
	replay = request("DELETE", `"1"`, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "")
	if replay.Code != 200 || replay.Body.String() != deleted.Body.String() || replay.Header().Get("ETag") != `"2"` {
		t.Fatalf("delete replay: %d %s", replay.Code, replay.Body)
	}
	current := request("GET", "", "", "")
	if current.Header().Get("ETag") != `"2"` || current.Body.String() != deleted.Body.String() {
		t.Fatal("replay reactivated removed destination")
	}
	for _, test := range []struct {
		method, etag, key, body string
		status                  int
		category                string
	}{
		{"PUT", `"0"`, key, strings.Replace(body, "PRIVATE", "CHANGED", 1), 409, "idempotency_conflict"},
		{"PUT", `"0"`, "cccccccccccccccccccccccccccccccc", body, 412, "registration_changed"},
		{"PUT", `"2"`, "cccccccccccccccccccccccccccccccc", body, 412, "registration_changed"},
		{"PUT", "", key, body, 400, "invalid_arguments"},
		{"PUT", `W/"2"`, key, body, 400, "invalid_arguments"},
		{"PUT", `"2"`, "", body, 400, "invalid_arguments"},
		{"DELETE", `"2"`, "cccccccccccccccccccccccccccccccc", "{}", 400, "invalid_arguments"},
	} {
		w := request(test.method, test.etag, test.key, test.body)
		if w.Code != test.status || !strings.Contains(w.Body.String(), `"error":"`+test.category+`"`) || strings.Contains(w.Body.String(), "PRIVATE") {
			t.Fatalf("%s: %d %s", test.method, w.Code, w.Body)
		}
	}
}
