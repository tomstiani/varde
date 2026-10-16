package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

const payload = `{"schemaVersion":1,"eventId":"event-http","title":"PRIVATE-TITLE","summary":"PRIVATE-BODY","priority":"high","observedAt":"2026-01-01T12:00:00Z"}`

func TestHTTPSubmissionAndOriginalRead(t *testing.T) {
	s, err := lifecycle.Open(filepath.Join(t.TempDir(), "varde.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	issue := func(role lifecycle.Role) lifecycle.Credential {
		t.Helper()
		i, err := s.CreateIdentity(context.Background(), role, "test")
		if err != nil {
			t.Fatal(err)
		}
		c, err := s.IssueCredential(context.Background(), i.ID)
		if err != nil {
			t.Fatal(err)
		}
		return c
	}
	sender := issue(lifecycle.Sender)
	device := issue(lifecycle.Installation)
	h := New(s)
	request := func(method, path, token, body string) *httptest.ResponseRecorder {
		t.Helper()
		req := httptest.NewRequest(method, path, strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		w := httptest.NewRecorder()
		h.ServeHTTP(w, req)
		return w
	}
	w := request("POST", "/v1/alerts", sender.Secret, payload)
	if w.Code != 201 {
		t.Fatalf("submit: %d %s", w.Code, w.Body.String())
	}
	var a lifecycle.Alert
	if err = json.Unmarshal(w.Body.Bytes(), &a); err != nil {
		t.Fatal(err)
	}
	w = request("GET", "/v1/alerts/"+a.ID, device.Secret, "")
	if w.Code != 200 || !bytes.Contains(w.Body.Bytes(), []byte("PRIVATE-TITLE")) {
		t.Fatalf("read: %d %s", w.Code, w.Body.String())
	}
	w = request("POST", "/v1/alerts", sender.Secret, payload)
	if w.Code != 200 {
		t.Fatalf("duplicate: %d", w.Code)
	}
	for _, tt := range []struct {
		name, method, path, token, body string
		code                            int
	}{
		{"missing", "POST", "/v1/alerts", "", payload, 401},
		{"invalid", "GET", "/v1/alerts/" + a.ID, strings.Repeat("a", 43), "", 401},
		{"sender read", "GET", "/v1/alerts/" + a.ID, sender.Secret, "", 403},
		{"device submit", "POST", "/v1/alerts", device.Secret, payload, 403},
		{"missing alert", "GET", "/v1/alerts/unknown", device.Secret, "", 404},
		{"conflict", "POST", "/v1/alerts", sender.Secret, strings.Replace(payload, "PRIVATE-TITLE", "changed", 1), 409},
		{"malformed", "POST", "/v1/alerts", sender.Secret, `{"PRIVATE-BODY":`, 400},
		{"validation", "POST", "/v1/alerts", sender.Secret, strings.Replace(payload, `"priority":"high"`, `"priority":"PRIVATE-BODY"`, 1), 422},
	} {
		t.Run(tt.name, func(t *testing.T) {
			w := request(tt.method, tt.path, tt.token, tt.body)
			if w.Code != tt.code {
				t.Fatalf("%d want %d: %s", w.Code, tt.code, w.Body.String())
			}
			var problem struct {
				Error   string `json:"error"`
				Message string `json:"message"`
			}
			if err := json.Unmarshal(w.Body.Bytes(), &problem); err != nil || problem.Error == "" || problem.Message == "" {
				t.Fatal("missing safe error category/message")
			}
			for _, secret := range []string{sender.Secret, device.Secret, "PRIVATE-TITLE", "PRIVATE-BODY"} {
				if strings.Contains(w.Body.String(), secret) {
					t.Fatal("error leaked input")
				}
			}
		})
	}
	for _, tt := range []struct {
		name, contentType, encoding, body string
		code                              int
	}{
		{"wrong type", "text/plain", "", payload, 415},
		{"charset", "application/json; charset=latin1", "", payload, 415},
		{"compressed", "application/json", "gzip", payload, 415},
		{"streamed oversized", "application/json", "", strings.Repeat(" ", lifecycle.MaxBodyBytes+1), 413},
	} {
		t.Run(tt.name, func(t *testing.T) {
			req := httptest.NewRequest("POST", "/v1/alerts", io.NopCloser(strings.NewReader(tt.body)))
			req.ContentLength = -1
			req.Header.Set("Authorization", "Bearer "+sender.Secret)
			req.Header.Set("Content-Type", tt.contentType)
			req.Header.Set("Content-Encoding", tt.encoding)
			w := httptest.NewRecorder()
			h.ServeHTTP(w, req)
			if w.Code != tt.code {
				t.Fatalf("%d want %d", w.Code, tt.code)
			}
		})
	}
	if err = s.RevokeCredential(context.Background(), device.ID); err != nil {
		t.Fatal(err)
	}
	if w = request("GET", "/v1/alerts/"+a.ID, device.Secret, ""); w.Code != 401 {
		t.Fatalf("revoked: %d", w.Code)
	}
}
