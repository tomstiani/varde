package httpapi

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/tomstiani/varde/backend/internal/fakepush"
	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func TestHTTPAcceptanceQueuesFakeDeliveryOutsideIngressAndWithoutBackfill(t *testing.T) {
	store, err := lifecycle.Open(filepath.Join(t.TempDir(), "delivery.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	ctx := context.Background()
	issue := func(role lifecycle.Role) lifecycle.Credential {
		identity, err := store.CreateIdentity(ctx, role, "synthetic")
		if err != nil {
			t.Fatal(err)
		}
		credential, err := store.IssueCredential(ctx, identity.ID)
		if err != nil {
			t.Fatal(err)
		}
		return credential
	}
	device, sender := issue(lifecycle.Installation), issue(lifecycle.Sender)
	server := httptest.NewServer(New(store))
	defer server.Close()
	request := func(method, path, token, body string) []byte {
		r, err := http.NewRequest(method, server.URL+path, strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		r.Header.Set("Authorization", "Bearer "+token)
		r.Header.Set("Content-Type", "application/json")
		if method == "PUT" {
			r.Header.Set("If-Match", `"0"`)
			r.Header.Set("Idempotency-Key", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
		}
		response, err := server.Client().Do(r)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		encoded, err := io.ReadAll(response.Body)
		if err != nil {
			t.Fatal(err)
		}
		if response.StatusCode != 200 && response.StatusCode != 201 {
			t.Fatalf("%s %s status=%d", method, path, response.StatusCode)
		}
		return encoded
	}
	request("POST", "/v1/alerts", sender.Secret, payload)
	request("PUT", "/v1/installation/push", device.Secret, `{"registrationId":"11111111111111111111111111111111","provider":"fcm","destination":"SYNTHETIC-PRIVATE"}`)
	future := strings.Replace(payload, "event-http", "future", 1)
	var alert lifecycle.Alert
	encoded := request("POST", "/v1/alerts", sender.Secret, future)
	if err := json.Unmarshal(encoded, &alert); err != nil {
		t.Fatal(err)
	}
	request("POST", "/v1/alerts", sender.Secret, future)
	provider := fakepush.New()
	dispatcher := store.NewPushDispatcher(provider, lifecycle.PushDispatchOptions{})
	if len(provider.Messages()) != 0 {
		t.Fatal("ingress called provider")
	}
	report, err := dispatcher.DispatchOne(ctx)
	if err != nil || report.Status != "delivered" {
		t.Fatalf("fake dispatch: %+v %v", report, err)
	}
	messages := provider.Messages()
	if len(messages) != 1 || messages[0].Data.AlertID != alert.ID || messages[0].Destination != "SYNTHETIC-PRIVATE" {
		t.Fatal("wrong fake-provider effect")
	}
	report, err = dispatcher.DispatchOne(ctx)
	if err != nil || report.Status != "idle" {
		t.Fatal("history or duplicate caused another delivery")
	}
}
