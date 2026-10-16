package lifecycle

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestCredentialSecretsAreNotStored(t *testing.T) {
	path := filepath.Join(t.TempDir(), "private.sqlite")
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	key := newCredential(t, s, Sender)
	for _, suffix := range []string{"", "-wal", "-shm"} {
		body, err := os.ReadFile(path + suffix)
		if os.IsNotExist(err) {
			continue
		}
		if err != nil {
			t.Fatal(err)
		}
		if bytes.Contains(body, []byte(key.Secret)) {
			t.Fatal("recoverable bearer secret persisted")
		}
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm()&0077 != 0 {
		t.Fatal("database permits other-user access")
	}
	for _, q := range []struct {
		pragma string
		want   int
	}{{"PRAGMA synchronous", 2}, {"PRAGMA foreign_keys", 1}} {
		var value int
		if err = s.db.QueryRow(q.pragma).Scan(&value); err != nil {
			t.Fatal(err)
		}
		if value != q.want {
			t.Fatalf("unsafe durability/integrity setting %s=%d", q.pragma, value)
		}
	}
	var mode string
	if err = s.db.QueryRow("PRAGMA journal_mode").Scan(&mode); err != nil || mode != "wal" {
		t.Fatalf("journal mode=%s err=%v", mode, err)
	}
	_, _, err = s.Submit(context.Background(), strings.Repeat("x", 43), []byte(validAlert))
	category(t, err, "unauthorized")
	_, err = s.IssueCredential(context.Background(), "not-an-owner")
	category(t, err, "not_found")
}
