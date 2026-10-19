package lifecycle_test

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/tomstiani/varde/backend/internal/httpapi"
	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func TestMetricCurrentContractOverHTTP(t *testing.T) {
	store, err := lifecycle.Open(filepath.Join(t.TempDir(), "varde.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	credential := func(role lifecycle.Role) string {
		identity, err := store.CreateIdentity(context.Background(), role, "synthetic")
		if err != nil {
			t.Fatal(err)
		}
		key, err := store.IssueCredential(context.Background(), identity.ID)
		if err != nil {
			t.Fatal(err)
		}
		return key.Secret
	}
	sender, reader := credential(lifecycle.Sender), credential(lifecycle.Installation)
	server := httptest.NewServer(httpapi.New(store))
	defer server.Close()
	request := func(method, path, key, body string) (int, string) {
		req, err := http.NewRequest(method, server.URL+path, strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Authorization", "Bearer "+key)
		req.Header.Set("Content-Type", "application/json")
		response, err := server.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		encoded, err := io.ReadAll(response.Body)
		if err != nil {
			t.Fatal(err)
		}
		return response.StatusCode, string(encoded)
	}
	body := `{"schemaVersion":1,"eventId":"synthetic-http","title":"Synthetic CPU alert","summary":"Complete supplied text.","priority":"high","observedAt":"2026-10-19T12:00:00Z","data":[{"type":"measurement","label":"CPU","value":83.00}]}`
	code, encoded := request("POST", "/v1/alerts", sender, body)
	var accepted lifecycle.Alert
	if code != 201 || json.Unmarshal([]byte(encoded), &accepted) != nil {
		t.Fatalf("submit: %d %s", code, encoded)
	}
	if _, err = store.CompleteSelectionFixture(context.Background(), map[string]string{"d0.metric": "p0", "summary": "p1"}); err != nil {
		t.Fatal(err)
	}
	code, encoded = request("GET", "/v1/alerts/"+accepted.ID, reader, "")
	if code != 200 || !strings.Contains(encoded, `"state":"rich","revision":1`) || !strings.Contains(encoded, `"richView":{"schemaVersion":1`) || !strings.Contains(encoded, `"value":83.00`) {
		t.Fatalf("read: %d %s", code, encoded)
	}
	code, encoded = request("GET", "/v1/alerts?order=acceptedAt", reader, "")
	if code != 200 || strings.Contains(encoded, "richView") || !strings.Contains(encoded, `"state":"rich"`) {
		t.Fatalf("history: %d %s", code, encoded)
	}
	code, encoded = request("POST", "/v1/alerts", sender, strings.Replace(body, `"value":83.00`, `"value":83.00,"samples":[]`, 1))
	if code != 422 || !strings.Contains(encoded, `"field":"data[0].samples"`) || strings.Contains(encoded, "Synthetic CPU alert") {
		t.Fatalf("unsupported input: %d %s", code, encoded)
	}
}
