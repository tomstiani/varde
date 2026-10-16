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

func TestHTTPHistoryContractAndSafeQueryErrors(t *testing.T) {
	store, err := lifecycle.Open(filepath.Join(t.TempDir(), "alerts.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	ctx := context.Background()
	owner, err := store.CreateIdentity(ctx, lifecycle.Installation, "phone")
	if err != nil {
		t.Fatal(err)
	}
	credential, err := store.IssueCredential(ctx, owner.ID)
	if err != nil {
		t.Fatal(err)
	}
	h := New(store)
	for _, query := range []string{"", "?order=acceptedAt&limit=1", "?limit=0", "?limit=101", "?limit=-1", "?limit=+1", "?limit=1&limit=2", "?order=other", "?unknown=PRIVATE", "?cursor=", "?cursor=%ZZ", "?cursor=PRIVATE", "?cursor=" + strings.Repeat("a", 3000)} {
		t.Run(query[:min(len(query), 50)], func(t *testing.T) {
			r := httptest.NewRequest("GET", "/v1/alerts"+query, nil)
			r.Header.Set("Authorization", "Bearer "+credential.Secret)
			w := httptest.NewRecorder()
			h.ServeHTTP(w, r)
			want := 400
			if query == "" || query == "?order=acceptedAt&limit=1" {
				want = 200
			}
			if w.Code != want {
				t.Fatalf("status=%d want=%d body=%s", w.Code, want, w.Body.String())
			}
			if strings.Contains(w.Body.String(), "PRIVATE") || strings.Contains(w.Body.String(), credential.Secret) {
				t.Fatal("leaked query or secret")
			}
			if want == 200 {
				var page struct {
					Items []json.RawMessage `json:"items"`
					Next  *string           `json:"nextCursor"`
				}
				if err := json.Unmarshal(w.Body.Bytes(), &page); err != nil || page.Items == nil || len(page.Items) != 0 || page.Next != nil {
					t.Fatalf("empty page: %s", w.Body.String())
				}
			}
			if w.Header().Get("Cache-Control") != "no-store" {
				t.Fatal("history may be cached")
			}
		})
	}
}
