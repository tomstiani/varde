// Package app wires local operator commands, HTTP serving, and lifecycle processing.
package app

import (
	"context"
	"encoding/json"
	"errors"
	"io"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func Run(ctx context.Context, args []string, out, diagnostics io.Writer) error {
	command, err := parseCommand(args)
	if err != nil {
		return err
	}
	if command.kind == checkGeneration {
		_, category := loadGeneration(command.generationConfig)
		if err := json.NewEncoder(out).Encode(struct {
			Event string `json:"event"`
		}{category}); err != nil {
			return errors.New("output_failed")
		}
		return nil
	}
	store, err := lifecycle.Open(command.databasePath)
	if err != nil {
		return err
	}
	defer store.Close()
	if command.kind == serveBackend {
		category, err := activateGeneration(ctx, store, command.generationConfig)
		if err != nil {
			return err
		}
		return serve(ctx, store, command.address, out, diagnostics, category)
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
