// Package demo verifies the backend using local operator commands and HTTP requests.
package demo

import (
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func Run(out io.Writer) error {
	directory, err := os.MkdirTemp("", "varde-demo-")
	if err != nil {
		return errors.New("temporary directory failed")
	}
	defer os.RemoveAll(directory)

	binary := filepath.Join(directory, "varde")
	if err = buildBackend(binary); err != nil {
		return err
	}
	database := filepath.Join(directory, "varde.sqlite")
	sender, err := issueCredential(binary, database, lifecycle.Sender)
	if err != nil {
		return err
	}
	reader, err := issueCredential(binary, database, lifecycle.Installation)
	if err != nil {
		return err
	}
	if err = verifyRestart(binary, database, sender, reader); err != nil {
		return err
	}

	if err = verifyFakePush(filepath.Join(directory, "push.sqlite")); err != nil {
		return err
	}
	_, err = fmt.Fprintln(out, "Verified: credential issuance, durable acceptance, restart, original retrieval, duplicate recovery, text fallback, and fake-provider push registration/retry. No live push was sent.")
	return err
}

func buildBackend(binary string) error {
	command := exec.Command("go", "build", "-o", binary, "github.com/tomstiani/varde/backend/cmd/varde")
	if err := command.Run(); err != nil {
		return errors.New("build failed; first check go build ./cmd/varde")
	}
	return nil
}
