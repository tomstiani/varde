package app

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

type generationTransport struct {
	target string
	calls  *atomic.Int32
}

func (transport generationTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	transport.calls.Add(1)
	replacement, _ := http.NewRequestWithContext(request.Context(), request.Method, transport.target, request.Body)
	return (&http.Transport{Proxy: nil}).RoundTrip(replacement)
}

func TestGenerationRestartNeverDispatchesOrRegenerates(t *testing.T) {
	var calls atomic.Int32
	fake := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(500) }))
	defer fake.Close()
	oldTransport := http.DefaultTransport
	http.DefaultTransport = generationTransport{fake.URL, &calls}
	defer func() { http.DefaultTransport = oldTransport }()
	client := &http.Client{Transport: &http.Transport{Proxy: nil}, Timeout: 3 * time.Second}
	directory := t.TempDir()
	database := filepath.Join(directory, "backend.sqlite")
	store, err := lifecycle.Open(database)
	if err != nil {
		t.Fatal(err)
	}
	issue := func(role lifecycle.Role) string {
		identity, err := store.CreateIdentity(context.Background(), role, "synthetic")
		if err != nil {
			t.Fatal(err)
		}
		credential, err := store.IssueCredential(context.Background(), identity.ID)
		if err != nil {
			t.Fatal(err)
		}
		return credential.Secret
	}
	sender, reader := issue(lifecycle.Sender), issue(lifecycle.Installation)
	store.Close()
	config := filepath.Join(directory, "config.json")
	key := filepath.Join(directory, "invented-key")
	if err := os.WriteFile(key, []byte("SENTINEL-invented-provider-secret"), 0600); err != nil {
		t.Fatal(err)
	}
	original := `{"schemaVersion":1,"eventId":"synthetic-byok","title":"Original title","summary":"SENTINEL-original","priority":"high","observedAt":"2026-01-01T12:00:00Z","data":[]}`
	var alertID string
	for _, tc := range []struct{ name, account, revision, keyPath, want string }{
		{"missing", "synthetic", "rev-1", key, "generation_configuration_invalid"},
		{"enabled", "synthetic", "rev-1", key, "generation_ready"},
		{"rotation", "synthetic", "rev-1", key + "-rotated", "generation_ready"},
		{"conflict", "other-project", "rev-1", key, "generation_configuration_invalid"},
		{"new-revision", "other-project", "rev-2", key, "generation_ready"},
		{"bad-key", "other-project", "rev-2", key + "-missing", "generation_key_unavailable"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if tc.name == "rotation" {
				if err := os.WriteFile(tc.keyPath, []byte("DIFFERENT-invented-key"), 0600); err != nil {
					t.Fatal(err)
				}
			}
			if tc.name != "missing" {
				body := map[string]any{"schemaVersion": 1, "enabled": true, "configId": tc.revision, "accountRef": tc.account, "model": "jev-1.13.0", "apiKeyFile": tc.keyPath}
				if err := os.WriteFile(config, mustJSON(t, body), 0600); err != nil {
					t.Fatal(err)
				}
			}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			output, input := io.Pipe()
			defer output.Close()
			var diagnostics bytes.Buffer
			done := make(chan error, 1)
			go func() {
				done <- Run(ctx, []string{"-db", database, "serve", "-listen", "127.0.0.1:0", "-generation-config", config}, input, &diagnostics)
				input.Close()
			}()
			var ready struct{ Address string }
			if err := json.NewDecoder(output).Decode(&ready); err != nil {
				t.Fatal(err)
			}
			request := func(method, path, credential, body string) (int, []byte) {
				t.Helper()
				r, _ := http.NewRequest(method, "http://"+ready.Address+path, strings.NewReader(body))
				r.Header.Set("Authorization", "Bearer "+credential)
				r.Header.Set("Content-Type", "application/json")
				response, err := client.Do(r)
				if err != nil {
					t.Fatal(err)
				}
				defer response.Body.Close()
				data, err := io.ReadAll(response.Body)
				if err != nil {
					t.Fatal(err)
				}
				return response.StatusCode, data
			}
			status, body := request("POST", "/v1/alerts", sender, original)
			if alertID == "" {
				if status != 201 {
					t.Fatalf("accept: %d %s", status, body)
				}
				var accepted struct{ ID string }
				json.Unmarshal(body, &accepted)
				alertID = accepted.ID
			} else {
				var duplicate struct{ ID string }
				json.Unmarshal(body, &duplicate)
				if status != 200 || duplicate.ID != alertID {
					t.Fatal("duplicate changed after restart")
				}
			}
			deadline := time.Now().Add(3 * time.Second)
			for {
				status, body = request("GET", "/v1/alerts/"+alertID, reader, "")
				var alert lifecycle.Alert
				if json.Unmarshal(body, &alert) != nil || status != 200 {
					t.Fatal("original unavailable")
				}
				if alert.Presentation.State == "fallback" {
					if alert.Presentation.Revision != 1 || alert.Original.Summary != "SENTINEL-original" || alert.Original.Title != "Original title" {
						t.Fatal("terminal original changed")
					}
					break
				}
				if time.Now().After(deadline) {
					t.Fatal("fallback timed out")
				}
				time.Sleep(10 * time.Millisecond)
			}
			newOriginal := strings.Replace(original, "synthetic-byok", "synthetic-byok-"+tc.name, 1)
			status, body = request("POST", "/v1/alerts", sender, newOriginal)
			var fresh struct{ ID string }
			if status != 201 || json.Unmarshal(body, &fresh) != nil {
				t.Fatal("new acceptance unavailable")
			}
			deadline = time.Now().Add(3 * time.Second)
			for {
				status, body = request("GET", "/v1/alerts/"+fresh.ID, reader, "")
				var alert lifecycle.Alert
				if status != 200 || json.Unmarshal(body, &alert) != nil {
					t.Fatal("new original unavailable")
				}
				if alert.Presentation.State == "fallback" && alert.Presentation.Revision == 1 {
					break
				}
				if time.Now().After(deadline) {
					t.Fatal("valid configuration queued model work instead of fallback")
				}
				time.Sleep(10 * time.Millisecond)
			}
			cancel()
			select {
			case err := <-done:
				if err != nil {
					t.Fatal(err)
				}
			case <-time.After(6 * time.Second):
				t.Fatal("shutdown timed out")
			}
			if !strings.Contains(diagnostics.String(), tc.want) {
				t.Fatalf("missing safe category: %q", diagnostics.String())
			}
			for _, private := range []string{"SENTINEL", key, config, tc.account, "DIFFERENT-invented-key"} {
				if strings.Contains(diagnostics.String(), private) {
					t.Fatal("diagnostic leaked private input")
				}
			}
		})
	}
	data, err := os.ReadFile(database)
	if err != nil {
		t.Fatal(err)
	}
	for _, private := range []string{"SENTINEL-invented-provider-secret", "DIFFERENT-invented-key", key, config} {
		if bytes.Contains(data, []byte(private)) {
			t.Fatal("database retained secret or file reference")
		}
	}
	if calls.Load() != 0 {
		t.Fatalf("provider requests: %d", calls.Load())
	}
}
