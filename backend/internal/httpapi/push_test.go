package httpapi

import (
	"context"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func TestHTTPInstallationPushStartsDisabledAndPrivate(t *testing.T) {
	store, err := lifecycle.Open(filepath.Join(t.TempDir(), "push.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	issue := func(role lifecycle.Role) lifecycle.Credential {
		identity, err := store.CreateIdentity(context.Background(), role, "test")
		if err != nil {
			t.Fatal(err)
		}
		credential, err := store.IssueCredential(context.Background(), identity.ID)
		if err != nil {
			t.Fatal(err)
		}
		return credential
	}
	device, sender := issue(lifecycle.Installation), issue(lifecycle.Sender)
	for _, test := range []struct {
		token  string
		status int
	}{{device.Secret, 200}, {sender.Secret, 403}, {"invalid", 401}} {
		r := httptest.NewRequest("GET", "/v1/installation/push", nil)
		r.Header.Set("Authorization", "Bearer "+test.token)
		w := httptest.NewRecorder()
		New(store).ServeHTTP(w, r)
		if w.Code != test.status {
			t.Fatalf("status=%d want=%d", w.Code, test.status)
		}
		if test.status == 200 && (w.Header().Get("ETag") != `"0"` || strings.TrimSpace(w.Body.String()) != `{"registrationId":null,"provider":null,"enabled":false,"baselineCursor":null}`) {
			t.Fatalf("unexpected initial registration: %s %s", w.Header().Get("ETag"), w.Body)
		}
		if w.Header().Get("Cache-Control") != "no-store" {
			t.Fatal("registration may be cached")
		}
	}
}
