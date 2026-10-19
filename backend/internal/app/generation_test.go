package app

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
)

func TestGenerationCheckValidatesOnlyExplicitConfiguration(t *testing.T) {
	key := filepath.Join(t.TempDir(), "invented-key")
	if err := os.WriteFile(key, []byte("invented-not-a-real-provider-key\r\n"), 0600); err != nil {
		t.Fatal(err)
	}
	valid := `{"schemaVersion":1,"enabled":true,"configId":"revision-1","accountRef":"synthetic","model":"jev-1.13.0","apiKeyFile":` + string(mustJSON(t, key)) + `}`
	for _, tc := range []struct{ name, body, want string }{
		{"enabled", valid, "generation_ready"},
		{"disabled", `{"schemaVersion":1,"enabled":false}`, "generation_disabled"},
		{"staged", strings.Replace(valid, `"enabled":true`, `"enabled":false`, 1), "generation_disabled"},
		{"unknown", strings.Replace(valid, `"model":`, `"endpoint":"https://private.invalid","model":`, 1), "generation_configuration_invalid"},
		{"alias", strings.Replace(valid, "jev-1.13.0", "jev-latest", 1), "generation_configuration_invalid"},
		{"duplicate", strings.Replace(valid, `"enabled":true`, `"enabled":false,"enabled":true`, 1), "generation_configuration_invalid"},
		{"null", `{"schemaVersion":1,"enabled":null}`, "generation_configuration_invalid"},
		{"missing", `{"schemaVersion":1}`, "generation_configuration_invalid"},
		{"trailing", valid + `{}`, "generation_configuration_invalid"},
		{"nested", `{"schemaVersion":1,"enabled":false,"model":[[[[[]]]]]}`, "generation_configuration_invalid"},
		{"lone-surrogate", strings.Replace(valid, string(mustJSON(t, key)), `"/tmp/\ud800"`, 1), "generation_configuration_invalid"},
		{"null-optional", `{"schemaVersion":1,"enabled":false,"apiKeyFile":null}`, "generation_configuration_invalid"},
		{"numeric-enabled", `{"schemaVersion":1,"enabled":1}`, "generation_configuration_invalid"},
		{"identifier-start", strings.Replace(valid, `"revision-1"`, `"1revision"`, 1), "generation_configuration_invalid"},
		{"identifier-length", strings.Replace(valid, `"revision-1"`, `"`+strings.Repeat("a", 65)+`"`, 1), "generation_configuration_invalid"},
		{"large", strings.Repeat(" ", 8193) + valid, "generation_configuration_invalid"},
		{"relative-key", strings.Replace(valid, string(mustJSON(t, key)), `"relative"`, 1), "generation_configuration_invalid"},
		{"missing-key", strings.Replace(valid, string(mustJSON(t, key)), string(mustJSON(t, key+"-missing")), 1), "generation_key_unavailable"},
		{"bad-staged-type", `{"schemaVersion":1,"enabled":false,"model":null}`, "generation_configuration_invalid"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			config := filepath.Join(t.TempDir(), "synthetic-config.json")
			if err := os.WriteFile(config, []byte(tc.body), 0600); err != nil {
				t.Fatal(err)
			}
			var out bytes.Buffer
			err := Run(context.Background(), []string{"generation", "check", "-generation-config", config}, &out, &out)
			if err != nil || out.String() != `{"event":"`+tc.want+`"}`+"\n" {
				t.Fatalf("check: %q %v", out.String(), err)
			}
		})
	}
}

func TestGenerationKeyRulesAndDisabledIsolation(t *testing.T) {
	for _, tc := range []struct {
		name, key string
		mode      os.FileMode
		want      string
	}{
		{"plain", "invented-key", 0600, "generation_ready"},
		{"LF", "invented-key\n", 0600, "generation_ready"},
		{"CRLF", "invented-key\r\n", 0600, "generation_ready"},
		{"CR", "invented-key\r", 0600, "generation_key_unavailable"},
		{"empty", "", 0600, "generation_key_unavailable"},
		{"spaces", " invented-key ", 0600, "generation_key_unavailable"},
		{"two-lines", "invented-key\n\n", 0600, "generation_key_unavailable"},
		{"non-ascii", "invented-é", 0600, "generation_key_unavailable"},
		{"large", strings.Repeat("k", 4097), 0600, "generation_key_unavailable"},
		{"boundary", strings.Repeat("k", 4096), 0600, "generation_ready"},
		{"world", "invented-key", 0604, "generation_key_unavailable"},
		{"group-write", "invented-key", 0620, "generation_key_unavailable"},
		{"group-read", "invented-key", 0640, "generation_ready"},
		{"unreadable", "invented-key", 0000, "generation_key_unavailable"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			directory := t.TempDir()
			key := filepath.Join(directory, "key")
			if err := os.WriteFile(key, []byte(tc.key), 0600); err != nil {
				t.Fatal(err)
			}
			if err := os.Chmod(key, tc.mode); err != nil {
				t.Fatal(err)
			}
			for _, enabled := range []bool{true, false} {
				config := filepath.Join(directory, "config.json")
				body := map[string]any{"schemaVersion": 1, "enabled": enabled, "configId": "test", "accountRef": "synthetic", "model": "jev-1.13.0", "apiKeyFile": key}
				if err := os.WriteFile(config, mustJSON(t, body), 0600); err != nil {
					t.Fatal(err)
				}
				var out bytes.Buffer
				err := Run(context.Background(), []string{"generation", "check", "-generation-config", config}, &out, &out)
				want := tc.want
				if !enabled {
					want = "generation_disabled"
				}
				if err != nil || out.String() != `{"event":"`+want+`"}`+"\n" {
					t.Fatalf("enabled=%v: %q %v", enabled, out.String(), err)
				}
			}
		})
	}
}

func TestGenerationConfigurationPreservesUnicodeFileReferences(t *testing.T) {
	for _, name := range []string{"key-\ufffd", "key-😀", `key-\ud800`} {
		directory := t.TempDir()
		key := filepath.Join(directory, name)
		config := filepath.Join(directory, "config.json")
		if err := os.WriteFile(key, []byte("invented-key"), 0600); err != nil {
			t.Fatal(err)
		}
		body := map[string]any{"schemaVersion": 1, "enabled": true, "configId": "test", "accountRef": "synthetic", "model": "jev-1.13.0", "apiKeyFile": key}
		if err := os.WriteFile(config, mustJSON(t, body), 0600); err != nil {
			t.Fatal(err)
		}
		var out bytes.Buffer
		if err := Run(context.Background(), []string{"generation", "check", "-generation-config", config}, &out, &out); err != nil || out.String() != `{"event":"generation_ready"}`+"\n" {
			t.Fatalf("unicode reference: %q %v", out.String(), err)
		}
	}
}

func TestGenerationKeyRejectsSpecialFilesWithoutBlocking(t *testing.T) {
	directory := t.TempDir()
	regular := filepath.Join(directory, "regular")
	if err := os.WriteFile(regular, []byte("invented-key"), 0600); err != nil {
		t.Fatal(err)
	}
	symlink := filepath.Join(directory, "symlink")
	if err := os.Symlink(regular, symlink); err != nil {
		t.Fatal(err)
	}
	fifo := filepath.Join(directory, "fifo")
	if err := syscall.Mkfifo(fifo, 0600); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{symlink, fifo, directory} {
		config := filepath.Join(directory, "config.json")
		body := map[string]any{"schemaVersion": 1, "enabled": true, "configId": "test", "accountRef": "synthetic", "model": "jev-1.13.0", "apiKeyFile": key}
		if err := os.WriteFile(config, mustJSON(t, body), 0600); err != nil {
			t.Fatal(err)
		}
		var out bytes.Buffer
		if err := Run(context.Background(), []string{"generation", "check", "-generation-config", config}, &out, &out); err != nil || out.String() != `{"event":"generation_key_unavailable"}`+"\n" {
			t.Fatalf("special file: %q %v", out.String(), err)
		}
	}
}

func mustJSON(t *testing.T, value any) []byte {
	t.Helper()
	b, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestGenerationCheckIsLocalAndDisabledByDefault(t *testing.T) {
	database := filepath.Join(t.TempDir(), "must-not-exist.sqlite")
	var out bytes.Buffer
	err := Run(context.Background(), []string{"-db", database, "generation", "check"}, &out, &out)
	if err != nil || out.String() != "{\"event\":\"generation_disabled\"}\n" {
		t.Fatalf("local check: %q %v", out.String(), err)
	}
	if _, err := os.Stat(database); !os.IsNotExist(err) {
		t.Fatal("local check opened storage")
	}
}
