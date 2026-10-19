package httpapi

import (
	"context"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func TestHTTPPushRejectsUnsafeRequestsWithoutChangingRegistration(t *testing.T) {
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
	body := `{"registrationId":"11111111111111111111111111111111","provider":"fcm","destination":"PRIVATE"}`
	h := New(store)
	for _, test := range []struct {
		name, body, query, etag, key string
		repeat                       bool
		status                       int
	}{
		{name: "unknown field", body: strings.TrimSuffix(body, "}") + `,"url":"PRIVATE"}`, status: 400},
		{name: "duplicate field", body: strings.TrimSuffix(body, "}") + `,"destination":"OTHER"}`, status: 400},
		{name: "null token", body: strings.Replace(body, `"PRIVATE"`, `null`, 1), status: 400},
		{name: "empty token", body: strings.Replace(body, "PRIVATE", "", 1), status: 400},
		{name: "wrong type", body: strings.Replace(body, `"PRIVATE"`, `23`, 1), status: 400},
		{name: "unsupported provider", body: strings.Replace(body, "fcm", "PRIVATE", 1), status: 400},
		{name: "uppercase identifier", body: strings.Replace(body, "11111111111111111111111111111111", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", 1), status: 400},
		{name: "oversized token", body: strings.Replace(body, "PRIVATE", strings.Repeat("x", 8193), 1), status: 400},
		{name: "invalid utf8", body: strings.Replace(body, "PRIVATE", string([]byte{0xff}), 1), status: 400},
		{name: "invalid surrogate", body: strings.Replace(body, "PRIVATE", `\ud800`, 1), status: 400},
		{name: "trailing json", body: body + `{}`, status: 400},
		{name: "oversized body", body: body + strings.Repeat(" ", lifecycle.MaxPushBodyBytes), status: 400},
		{name: "unknown query", body: body, query: "?destination=PRIVATE", status: 400},
		{name: "repeated headers", body: body, repeat: true, status: 400},
		{name: "unquoted version", body: body, etag: "0", status: 400},
		{name: "list version", body: body, etag: `"0", "1"`, status: 400},
		{name: "wildcard", body: body, etag: "*", status: 400},
		{name: "negative", body: body, etag: `"-1"`, status: 400},
		{name: "leading zero", body: body, etag: `"00"`, status: 400},
		{name: "overflow", body: body, etag: `"9223372036854775808"`, status: 400},
		{name: "malformed key", body: body, key: "PRIVATE", status: 400},
	} {
		t.Run(test.name, func(t *testing.T) {
			r := httptest.NewRequest("PUT", "/v1/installation/push"+test.query, strings.NewReader(test.body))
			r.Header.Set("Authorization", "Bearer "+device.Secret)
			r.Header.Set("Content-Type", "application/json")
			etag, key := test.etag, test.key
			if etag == "" {
				etag = `"0"`
			}
			if key == "" {
				key = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
			}
			r.Header.Set("If-Match", etag)
			r.Header.Set("Idempotency-Key", key)
			if test.repeat {
				r.Header.Add("If-Match", etag)
			}
			w := httptest.NewRecorder()
			h.ServeHTTP(w, r)
			if w.Code != test.status || strings.Contains(w.Body.String(), "PRIVATE") {
				t.Fatalf("status=%d body=%s", w.Code, w.Body)
			}
			current, err := store.PushRegistration(context.Background(), device.Secret)
			if err != nil || current.Enabled || current.Version != 0 {
				t.Fatalf("invalid request mutated state: %+v %v", current, err)
			}
		})
	}
	r := httptest.NewRequest("PUT", "/v1/installation/push", strings.NewReader(body))
	r.Header.Set("Authorization", "Bearer "+device.Secret)
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("If-Match", `"0"`)
	r.Header.Set("Idempotency-Key", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 200 {
		t.Fatalf("invalid request consumed replay key: %d %s", w.Code, w.Body)
	}
}
