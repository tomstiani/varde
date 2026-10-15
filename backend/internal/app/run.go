// Package app wires local operator commands, HTTP serving, and lifecycle processing.
package app

import (
	"context"
	"encoding/json"
	"errors"
	"io"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func Run(ctx context.Context, args []string, out io.Writer) error {
	command, err := parseCommand(args)
	if err != nil {
		return err
	}
	store, err := lifecycle.Open(command.databasePath)
	if err != nil {
		return err
	}
	defer store.Close()
	if command.kind == serveBackend {
		return serve(ctx, store, command.address, out)
	}

	result, err := runOperatorCommand(ctx, store, command)
	if err != nil {
		return err
	}
	if err = json.NewEncoder(out).Encode(result); err != nil {
		return errors.New("output_failed")
	}
	return nil
}
