package httpapi

import (
	"context"
	"encoding/json"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"testing"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func TestHTTPChangeRecoveryAndSafeRequestValidation(t *testing.T) {
	store, err := lifecycle.Open(filepath.Join(t.TempDir(), "changes.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	ctx := context.Background()
	issue := func(role lifecycle.Role) lifecycle.Credential {
		owner, err := store.CreateIdentity(ctx, role, "test")
		if err != nil {
			t.Fatal(err)
		}
		credential, err := store.IssueCredential(ctx, owner.ID)
		if err != nil {
			t.Fatal(err)
		}
		return credential
	}
	device, sender := issue(lifecycle.Installation), issue(lifecycle.Sender)
	h := New(store)
	request := func(method, path, token, body string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, path, strings.NewReader(body))
		r.Header.Set("Authorization", "Bearer "+token)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w
	}
	checkpoint := request("POST", "/v1/changes/checkpoint", device.Secret, "")
	if checkpoint.Code != 200 {
		t.Fatalf("checkpoint: %d %s", checkpoint.Code, checkpoint.Body)
	}
	var position lifecycle.Checkpoint
	if err := json.Unmarshal(checkpoint.Body.Bytes(), &position); err != nil || position.Cursor == "" {
		t.Fatal("missing checkpoint")
	}
	alert, _, err := store.Submit(ctx, sender.Secret, []byte(payload))
	if err != nil {
		t.Fatal(err)
	}
	path := "/v1/changes?cursor=" + url.QueryEscape(position.Cursor)
	response := request("GET", path, device.Secret, "")
	var page lifecycle.ChangesPage
	if err := json.Unmarshal(response.Body.Bytes(), &page); err != nil || response.Code != 200 || len(page.Items) != 1 || page.Items[0].AlertID != alert.ID || page.NextCursor == "" || page.HasMore {
		t.Fatalf("changes: %d %s", response.Code, response.Body)
	}
	if response.Header().Get("Cache-Control") != "no-store" || strings.Contains(response.Body.String(), "PRIVATE") {
		t.Fatal("unsafe recovery content")
	}
	for _, test := range []struct {
		method, path, token, body string
		status                    int
	}{
		{"POST", "/v1/changes/checkpoint", sender.Secret, "", 403},
		{"GET", path, sender.Secret, "", 403},
		{"GET", path, "wrong", "", 401},
		{"POST", "/v1/changes/checkpoint?unknown=PRIVATE", device.Secret, "", 400},
		{"POST", "/v1/changes/checkpoint", device.Secret, "{}", 400},
		{"GET", "/v1/changes", device.Secret, "", 400},
		{"GET", path + "&limit=0", device.Secret, "", 400},
		{"GET", path + "&limit=101", device.Secret, "", 400},
		{"GET", path + "&limit=+1", device.Secret, "", 400},
		{"GET", path + "&limit=1&limit=2", device.Secret, "", 400},
		{"GET", path + "&unknown=PRIVATE", device.Secret, "", 400},
		{"GET", "/v1/changes?cursor=PRIVATE", device.Secret, "", 400},
		{"GET", "/v1/changes?cursor=" + strings.Repeat("a", 3000), device.Secret, "", 400},
	} {
		result := request(test.method, test.path, test.token, test.body)
		if result.Code != test.status || strings.Contains(result.Body.String(), "PRIVATE") || strings.Contains(result.Body.String(), device.Secret) {
			t.Fatalf("%s status=%d want=%d body=%s", test.method, result.Code, test.status, result.Body)
		}
	}
	if err := store.RevokeCredential(ctx, device.ID); err != nil {
		t.Fatal(err)
	}
	if response := request("GET", path, device.Secret, ""); response.Code != 401 {
		t.Fatalf("revoked: %d", response.Code)
	}
}
