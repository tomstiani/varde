package app

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

func TestExecutableGenerationChecksAndStartupAreOffline(t *testing.T) {
	binary := buildExecutable(t)
	directory := t.TempDir()
	key := filepath.Join(directory, "fake-key")
	config := filepath.Join(directory, "generation.json")
	if err := os.WriteFile(key, []byte("SENTINEL-invented-key"), 0600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("TYPESAFE_API_KEY", "SENTINEL-ignored-ambient-key")
	for _, enabled := range []bool{false, true} {
		body := map[string]any{"schemaVersion": 1, "enabled": enabled, "configId": "offline", "accountRef": "synthetic", "model": "jev-1.13.0", "apiKeyFile": key}
		if err := os.WriteFile(config, mustJSON(t, body), 0600); err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
		command := exec.CommandContext(ctx, binary, "generation", "check", "-generation-config", config)
		output, err := command.CombinedOutput()
		cancel()
		want := "generation_disabled"
		if enabled {
			want = "generation_ready"
		}
		if err != nil || string(output) != `{"event":"`+want+`"}`+"\n" {
			t.Fatalf("executable check: %q %v", output, err)
		}
	}
	for _, selected := range []string{config, config + "-missing"} {
		ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
		command := exec.CommandContext(ctx, binary, "-db", filepath.Join(directory, "process.sqlite"), "serve", "-listen", "127.0.0.1:0", "-generation-config", selected)
		var diagnostics bytes.Buffer
		command.Stderr = &diagnostics
		output, err := command.StdoutPipe()
		if err != nil {
			cancel()
			t.Fatal(err)
		}
		if err = command.Start(); err != nil {
			cancel()
			t.Fatal(err)
		}
		var ready struct{ Address string }
		err = json.NewDecoder(output).Decode(&ready)
		if err == nil {
			response, requestErr := http.Get("http://" + ready.Address + "/v1/alerts")
			if requestErr != nil {
				t.Error(requestErr)
			} else {
				io.Copy(io.Discard, response.Body)
				response.Body.Close()
				if response.StatusCode != 401 {
					t.Error("original service unavailable")
				}
			}
		} else {
			t.Error("missing executable readiness", err)
		}
		command.Process.Signal(syscall.SIGTERM)
		err = command.Wait()
		cancel()
		if err != nil {
			t.Fatal(err)
		}
		want := "generation_ready"
		if selected != config {
			want = generationInvalid
		}
		if !strings.Contains(diagnostics.String(), want) {
			t.Fatal("missing safe generation event")
		}
		if strings.Contains(diagnostics.String(), "SENTINEL") || strings.Contains(diagnostics.String(), directory) {
			t.Fatal("executable leaked secret/path")
		}
	}
}
