package app

import (
	"context"
	"errors"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func runOperatorCommand(ctx context.Context, store *lifecycle.Lifecycle, command command) (any, error) {
	switch command.kind {
	case createIdentity:
		return store.CreateIdentity(ctx, command.role, command.name)
	case issueCredential:
		return store.IssueCredential(ctx, command.identityID)
	case revokeCredential:
		err := store.RevokeCredential(ctx, command.credentialID)
		return struct {
			Revoked bool `json:"revoked"`
		}{true}, err
	default:
		return nil, errors.New("invalid_arguments")
	}
}
