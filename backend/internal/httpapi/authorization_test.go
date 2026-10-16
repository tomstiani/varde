package httpapi

import (
	"context"
	"io"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func TestAuthorizationProtectsBothSidesOfBodyReading(t *testing.T) {
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

	read := false
	request := httptest.NewRequest("POST", "/v1/alerts", &readHook{reader: strings.NewReader(payload), beforeRead: func() { read = true }})
	request.Header.Set("Content-Type", "application/json")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != 401 || read {
		t.Fatal("unauthorized request consumed its body")
	}

	request = httptest.NewRequest("POST", "/v1/alerts", &readHook{
		reader: strings.NewReader(payload),
		beforeRead: func() {
			if err := store.RevokeCredential(context.Background(), credential.ID); err != nil {
				t.Fatal(err)
			}
		},
	})
	request.Header.Set("Authorization", "Bearer "+credential.Secret)
	request.Header.Set("Content-Type", "application/json")
	response = httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != 401 {
		t.Fatal("acceptance did not recheck revocation after body reading")
	}

	replacement, err := store.IssueCredential(context.Background(), identity.ID)
	if err != nil {
		t.Fatal(err)
	}
	_, created, err := store.Submit(context.Background(), replacement.Secret, []byte(payload))
	if err != nil || !created {
		t.Fatalf("rejected request created an alert: created=%v error=%v", created, err)
	}
}

type readHook struct {
	reader     io.Reader
	beforeRead func()
}

func (r *readHook) Read(buffer []byte) (int, error) {
	if r.beforeRead != nil {
		r.beforeRead()
		r.beforeRead = nil
	}
	return r.reader.Read(buffer)
}
