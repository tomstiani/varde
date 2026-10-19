package app

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func executableEvents(t *testing.T, path string) []lifecycle.WorkerEvent {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	lines := bytes.Split(data, []byte("\n"))
	var events []lifecycle.WorkerEvent
	for _, line := range lines[:len(lines)-1] {
		if len(line) == 0 {
			continue
		}
		var event lifecycle.WorkerEvent
		decoder := json.NewDecoder(bytes.NewReader(line))
		decoder.DisallowUnknownFields()
		if decoder.Decode(&event) != nil {
			t.Fatal("stderr contained a non-structured diagnostic")
		}
		events = append(events, event)
	}
	return events
}

func awaitExecutableEvent(t *testing.T, path, name string, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		for _, event := range executableEvents(t, path) {
			if event.Event == name {
				return
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("missing executable event:", name)
}

func buildExecutable(t *testing.T) string {
	t.Helper()
	binary := filepath.Join(t.TempDir(), "varde")
	build := exec.Command("go", "build", "-o", binary, "./cmd/varde")
	build.Dir = "../.."
	if err := build.Run(); err != nil {
		t.Fatal("could not build executable:", err)
	}
	return binary
}

func TestBlockedStderrDoesNotPreventExecutableFailureExit(t *testing.T) {
	binary := buildExecutable(t)
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	defer writer.Close()
	filling := make(chan struct{})
	filled := make(chan struct{})
	go func() {
		close(filling)
		_, _ = writer.Write(bytes.Repeat([]byte("x"), 1<<20))
		close(filled)
	}()
	<-filling
	select {
	case <-filled:
		t.Fatal("pipe did not apply backpressure")
	case <-time.After(50 * time.Millisecond):
	}
	process := exec.Command(binary, "--invalid-argument")
	process.Stderr = writer
	if err = process.Start(); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- process.Wait() }()
	t.Cleanup(func() { reader.Close(); writer.Close(); <-filled })
	select {
	case err = <-done:
		var exit *exec.ExitError
		if !errors.As(err, &exit) || exit.ExitCode() != 1 {
			t.Fatalf("wrong failure exit: %v", err)
		}
	case <-time.After(time.Second):
		_ = process.Process.Kill()
		<-done
		t.Fatal("blocked terminal diagnostic prevented process exit")
	}
}

func TestExecutableRecoversAndBoundsQueuedRequestsAndShutdown(t *testing.T) {
	directory := t.TempDir()
	binary := buildExecutable(t)
	database := filepath.Join(directory, "synthetic.sqlite")
	operator := func(arguments ...string) []byte {
		command := exec.Command(binary, append([]string{"-db", database}, arguments...)...)
		output, err := command.Output()
		if err != nil {
			t.Fatal("operator command failed:", err)
		}
		return output
	}
	issue := func(role string) string {
		var identity lifecycle.Identity
		if json.Unmarshal(operator("identity", "create", role, "synthetic"), &identity) != nil {
			t.Fatal("invalid identity response")
		}
		var credential lifecycle.Credential
		if json.Unmarshal(operator("credential", "issue", identity.ID), &credential) != nil {
			t.Fatal("invalid issuance response")
		}
		return credential.Secret
	}
	sender, reader := issue("sender"), issue("installation")
	stdoutPath, stderrPath := filepath.Join(directory, "stdout.json"), filepath.Join(directory, "stderr.jsonl")
	stdout, err := os.OpenFile(stdoutPath, os.O_CREATE|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	defer stdout.Close()
	stderr, err := os.OpenFile(stderrPath, os.O_CREATE|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	defer stderr.Close()
	process := exec.Command(binary, "-db", database, "serve", "-listen", "127.0.0.1:0")
	process.Stdout = stdout
	process.Stderr = stderr
	if err = process.Start(); err != nil {
		t.Fatal(err)
	}
	exited := make(chan struct{})
	var exitError error
	go func() { exitError = process.Wait(); close(exited) }()
	t.Cleanup(func() {
		select {
		case <-exited:
			return
		default:
		}
		_ = process.Process.Signal(syscall.SIGTERM)
		select {
		case <-exited:
		case <-time.After(7 * time.Second):
			_ = process.Process.Kill()
			<-exited
		}
	})
	var ready struct {
		Event   string
		Address string
	}
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		data, _ := os.ReadFile(stdoutPath)
		if json.Unmarshal(data, &ready) == nil && ready.Event == "listening" {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if ready.Address == "" {
		t.Fatal("missing executable readiness")
	}
	address := "http://" + ready.Address
	client := &http.Client{Timeout: 7 * time.Second}
	original := `{"schemaVersion":1,"eventId":"synthetic","title":"Synthetic","summary":"SENTINEL-private-original","priority":"normal","observedAt":"2026-01-01T12:00:00Z"}`
	submit, _ := http.NewRequest("POST", address+"/v1/alerts", strings.NewReader(original))
	submit.Header.Set("Authorization", "Bearer "+sender)
	submit.Header.Set("Content-Type", "application/json")
	response, err := client.Do(submit)
	if err != nil {
		t.Fatal(err)
	}
	var accepted struct{ ID string }
	err = json.NewDecoder(response.Body).Decode(&accepted)
	response.Body.Close()
	if err != nil || response.StatusCode != 201 {
		t.Fatal("initial durable acceptance failed")
	}
	release := holdBackendWriter(t, database)
	awaitExecutableEvent(t, stderrPath, "worker_recovery_started", 4*time.Second)
	type result struct {
		err     error
		status  int
		elapsed time.Duration
		valid   bool
		detail  string
	}
	results := make(chan result, 12)
	for range 12 {
		go func() {
			started := time.Now()
			request, _ := http.NewRequest("GET", address+"/v1/alerts", nil)
			request.Header.Set("Authorization", "Bearer "+reader)
			response, err := client.Do(request)
			if err != nil {
				results <- result{err: err}
				return
			}
			payload, err := io.ReadAll(response.Body)
			response.Body.Close()
			var failure struct{ Error string }
			valid := json.Unmarshal(payload, &failure) == nil && failure.Error == "storage_unavailable" && response.Header.Get("Content-Type") == "application/json" && response.Header.Get("Cache-Control") == "no-store" && response.Header.Get("Retry-After") == "5"
			results <- result{err: err, status: response.StatusCode, elapsed: time.Since(started), valid: valid, detail: fmt.Sprintf("category=%q content-type=%q cache=%q retry=%q", failure.Error, response.Header.Get("Content-Type"), response.Header.Get("Cache-Control"), response.Header.Get("Retry-After"))}
		}()
	}
	for range 12 {
		result := <-results
		if result.err != nil || result.status != 503 || !result.valid || result.elapsed > 6500*time.Millisecond {
			t.Errorf("queued request was not bounded safely: status=%d duration=%v error=%v %s", result.status, result.elapsed, result.err, result.detail)
		}
	}
	release()
	awaitExecutableEvent(t, stderrPath, "worker_recovery_succeeded", 8*time.Second)
	request, _ := http.NewRequest("GET", address+"/v1/alerts/"+accepted.ID, nil)
	request.Header.Set("Authorization", "Bearer "+reader)
	response, err = client.Do(request)
	if err != nil {
		t.Fatal("backend did not recover:", err)
	}
	var retained struct {
		Original     struct{ Summary string }
		Presentation struct{ State string }
	}
	err = json.NewDecoder(response.Body).Decode(&retained)
	response.Body.Close()
	if err != nil || response.StatusCode != 200 || retained.Original.Summary != "SENTINEL-private-original" || retained.Presentation.State != "fallback" {
		t.Fatal("recovery did not preserve and complete the original")
	}
	// Hold storage through SIGTERM; shutdown must not require the lock to be released.
	releaseAgain := holdBackendWriter(t, database)
	defer releaseAgain()
	time.Sleep(200 * time.Millisecond)
	started := time.Now()
	if err = process.Process.Signal(syscall.SIGTERM); err != nil {
		t.Fatal(err)
	}
	select {
	case <-exited:
	case <-time.After(2 * time.Second):
		t.Fatal("contention blocked shutdown")
	}
	if exitError != nil || time.Since(started) > 2*time.Second {
		t.Fatal("executable shutdown failed:", exitError)
	}
	data, err := os.ReadFile(stdoutPath)
	if err != nil || json.Unmarshal(data, &ready) != nil {
		t.Fatal("recovery diagnostics changed stdout protocol")
	}
	diagnostics, err := os.ReadFile(stderrPath)
	if err != nil {
		t.Fatal(err)
	}
	for _, secret := range []string{sender, reader, "SENTINEL-private-original"} {
		if bytes.Contains(diagnostics, []byte(secret)) {
			t.Fatal("private content in diagnostics")
		}
	}
	for _, event := range executableEvents(t, stderrPath) {
		if event.Event == "generation_configuration" && event.Stage == "generation" && event.Category == "generation_disabled" && event.RetryCount == 0 && event.ElapsedMilliseconds == 0 {
			continue
		}
		if event.Stage != "fallback_worker" || event.Category != "storage_busy" {
			t.Fatal("unexpected diagnostic category")
		}
	}
}
