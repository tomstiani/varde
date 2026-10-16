package app

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func TestServerReadinessAndShutdown(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	r, w := io.Pipe()
	defer r.Close()
	done := make(chan error, 1)
	go func() {
		done <- Run(ctx, []string{"-db", filepath.Join(t.TempDir(), "server.sqlite"), "serve", "-listen", "127.0.0.1:0"}, w)
		_ = w.Close()
	}()
	ready := make(chan string, 1)
	go func() {
		var event struct {
			Address string `json:"address"`
		}
		_ = json.NewDecoder(r).Decode(&event)
		ready <- event.Address
	}()
	select {
	case address := <-ready:
		if address == "" {
			t.Fatal("missing readiness address")
		}
		response, err := http.Get("http://" + address + "/v1/alerts/unknown")
		if err != nil {
			t.Fatal(err)
		}
		_ = response.Body.Close()
		if response.StatusCode != 401 {
			t.Fatalf("unauthenticated read: %d", response.StatusCode)
		}
	case <-time.After(2 * time.Second):
		cancel()
		<-done
		t.Fatal("server did not announce readiness")
	}
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(7 * time.Second):
		t.Fatal("shutdown timed out")
	}
	var out bytes.Buffer
	err := Run(context.Background(), []string{"-db", filepath.Join(t.TempDir(), "external.sqlite"), "serve", "-listen", "0.0.0.0:8080"}, &out)
	if err == nil || err.Error() != "loopback_required" {
		t.Fatal("non-loopback bind permitted")
	}
}

func TestOperatorCredentialCommands(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "varde.sqlite")
	invoke := func(args ...string) string {
		t.Helper()
		var out bytes.Buffer
		if err := Run(ctx, append([]string{"-db", path}, args...), &out); err != nil {
			t.Fatal(err)
		}
		return out.String()
	}
	var sender lifecycle.Identity
	if err := json.Unmarshal([]byte(invoke("identity", "create", "sender", "n8n")), &sender); err != nil {
		t.Fatal(err)
	}
	var key lifecycle.Credential
	if err := json.Unmarshal([]byte(invoke("credential", "issue", sender.ID)), &key); err != nil {
		t.Fatal(err)
	}
	if len(key.Secret) != 43 || key.IdentityID != sender.ID {
		t.Fatal("invalid credential output")
	}
	if text := invoke("credential", "revoke", key.ID); strings.Contains(text, key.Secret) {
		t.Fatal("revoke exposed secret")
	}
	s, err := lifecycle.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err = s.Authorize(ctx, key.Secret, lifecycle.Sender); err == nil {
		t.Fatal("revocation not persisted")
	}
	var out bytes.Buffer
	err = Run(ctx, []string{"-db", path, "-PRIVATE-CREDENTIAL"}, &out)
	if err == nil || strings.Contains(err.Error(), "PRIVATE-CREDENTIAL") || out.Len() != 0 {
		t.Fatal("argument error leaked input")
	}
}
