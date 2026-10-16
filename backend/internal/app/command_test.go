package app

import (
	"bytes"
	"context"
	"io"
	"os"
	"path/filepath"
	"testing"
)

func TestMalformedCommandsDoNotAcquireStorage(t *testing.T) {
	cases := []struct {
		name string
		args []string
	}{
		{"unknown command", []string{"unknown-command"}},
		{"unknown identity action", []string{"identity", "remove", "sender", "n8n"}},
		{"missing identity name", []string{"identity", "create", "sender"}},
		{"extra credential argument", []string{"credential", "issue", "owner-id", "extra"}},
		{"unknown credential action", []string{"credential", "remove", "cred-id"}},
		{"unknown serve flag", []string{"serve", "-unknown-flag"}},
		{"missing serve flag value", []string{"serve", "-listen"}},
		{"serve positional argument", []string{"serve", "unexpected"}},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			root := t.TempDir()
			parent := filepath.Join(root, "must-not-exist")
			var output bytes.Buffer
			args := append([]string{"-db", filepath.Join(parent, "varde.sqlite")}, test.args...)
			err := Run(context.Background(), args, &output, io.Discard)
			if err == nil || err.Error() != "invalid_arguments" || output.Len() != 0 {
				t.Fatalf("unexpected command rejection: error=%v output=%q", err, output.String())
			}
			if _, err = os.Stat(parent); !os.IsNotExist(err) {
				t.Fatalf("invalid grammar acquired storage: %v", err)
			}
			blocked := filepath.Join(root, "not-a-directory")
			if err = os.WriteFile(blocked, []byte("sentinel"), 0600); err != nil {
				t.Fatal(err)
			}
			args = append([]string{"-db", filepath.Join(blocked, "varde.sqlite")}, test.args...)
			if err = Run(context.Background(), args, &output, io.Discard); err == nil || err.Error() != "invalid_arguments" {
				t.Fatalf("storage error masked grammar error: %v", err)
			}
		})
	}
}
