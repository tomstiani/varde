package lifecycle

import (
	"bufio"
	"bytes"
	"context"
	"database/sql"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestCrashHelper(t *testing.T) {
	mode := os.Getenv("VARDE_TEST_CRASH")
	if mode == "" {
		return
	}
	store, err := Open(os.Getenv("VARDE_TEST_DATABASE"))
	if err != nil {
		t.Fatal(err)
	}
	pause := func() { fmt.Println("ready"); time.Sleep(time.Hour) }
	if mode == "before" {
		store.beforeAcceptCommit = func(*sql.Conn) error { pause(); return nil }
	} else {
		store.afterAcceptCommit = pause
	}
	_, _, err = store.Submit(context.Background(), os.Getenv("VARDE_TEST_SENDER"), []byte(validAlert))
	t.Fatal("helper failed to pause:", err)
}

func TestKilledProcessRecoversAcceptance(t *testing.T) {
	for _, mode := range []string{"before", "after"} {
		t.Run(mode, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "crash.sqlite")
			store, err := Open(path)
			if err != nil {
				t.Fatal(err)
			}
			sender := newCredential(t, store, Sender)
			reader := newCredential(t, store, Installation)
			if err = store.Close(); err != nil {
				t.Fatal(err)
			}

			killDuringSubmit(t, path, sender.Secret, mode)
			store, err = Open(path)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { store.Close() })
			if mode == "before" {
				counts(t, store, 0, 0, 0)
			} else {
				counts(t, store, 1, 1, 1)
			}
			alert, created, err := store.Submit(context.Background(), sender.Secret, []byte(validAlert))
			if err != nil || created != (mode == "before") {
				t.Fatalf("recovered submit: %v %v", created, err)
			}
			got, err := store.Read(context.Background(), reader.Secret, alert.ID)
			if err != nil || got.Original.Title != "Disk pressure" || got.Presentation.State != "pending" {
				t.Fatalf("recovered original: %+v %v", got, err)
			}

			runWorker(t, store)
			waitFallback(t, store, reader.Secret, alert.ID)
			time.Sleep(150 * time.Millisecond)
			counts(t, store, 1, 1, 2)
		})
	}
}

func killDuringSubmit(t *testing.T, path, token, mode string) {
	t.Helper()
	command := exec.Command(os.Args[0], "-test.run=^TestCrashHelper$")
	command.Env = append(os.Environ(), "VARDE_TEST_CRASH="+mode, "VARDE_TEST_DATABASE="+path, "VARDE_TEST_SENDER="+token)
	var stderr bytes.Buffer
	command.Stderr = &stderr
	stdout, err := command.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err = command.Start(); err != nil {
		t.Fatal(err)
	}

	ready := make(chan bool, 1)
	go func() { line, _ := bufio.NewReader(stdout).ReadString('\n'); ready <- line == "ready\n" }()
	select {
	case ok := <-ready:
		if !ok {
			_ = command.Process.Kill()
			_ = command.Wait()
			t.Fatalf("helper not ready: %s", stderr.String())
		}
	case <-time.After(10 * time.Second):
		_ = command.Process.Kill()
		_ = command.Wait()
		t.Fatal("helper timeout")
	}
	if err = command.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	if err = command.Wait(); err == nil {
		t.Fatal("expected killed subprocess")
	}
	if strings.Contains(stderr.String(), token) || strings.Contains(stderr.String(), "Backup volume near limit") {
		t.Fatal("child diagnostics leaked private data")
	}
}
