package app

import (
	"errors"
	"flag"
	"io"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

type commandKind uint8

const (
	createIdentity commandKind = iota + 1
	issueCredential
	revokeCredential
	serveBackend
	checkGeneration
)

type command struct {
	databasePath     string
	kind             commandKind
	role             lifecycle.Role
	name             string
	identityID       string
	credentialID     string
	address          string
	generationConfig string
}

func parseCommand(args []string) (command, error) {
	flags := flag.NewFlagSet("varde", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	databasePath := flags.String("db", "data/varde.sqlite", "SQLite file")
	if err := flags.Parse(args); err != nil {
		return command{}, errors.New("invalid_arguments")
	}
	args = flags.Args()
	if len(args) == 0 {
		return command{}, errors.New("usage: varde [-db PATH] identity create ROLE NAME | credential issue OWNER_ID | credential revoke CREDENTIAL_ID | serve [-listen 127.0.0.1:8080] [-generation-config PATH] | generation check [-generation-config PATH]")
	}

	parsed := command{databasePath: *databasePath}
	switch {
	case len(args) == 4 && args[0] == "identity" && args[1] == "create":
		parsed.kind, parsed.role, parsed.name = createIdentity, lifecycle.Role(args[2]), args[3]
	case len(args) == 3 && args[0] == "credential" && args[1] == "issue":
		parsed.kind, parsed.identityID = issueCredential, args[2]
	case len(args) == 3 && args[0] == "credential" && args[1] == "revoke":
		parsed.kind, parsed.credentialID = revokeCredential, args[2]
	case args[0] == "serve":
		address, config, err := parseGenerationFlags(args[1:], true)
		if err != nil {
			return command{}, err
		}
		parsed.kind, parsed.address, parsed.generationConfig = serveBackend, address, config
	case len(args) >= 2 && args[0] == "generation" && args[1] == "check":
		_, config, err := parseGenerationFlags(args[2:], false)
		if err != nil {
			return command{}, err
		}
		parsed.kind, parsed.generationConfig = checkGeneration, config
	default:
		return command{}, errors.New("invalid_arguments")
	}
	return parsed, nil
}

func parseGenerationFlags(args []string, serving bool) (string, string, error) {
	flags := flag.NewFlagSet("generation", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	address := "127.0.0.1:8080"
	if serving {
		flags.StringVar(&address, "listen", address, "loopback listen address")
	}
	config := flags.String("generation-config", "", "absolute local generation configuration path")
	if err := flags.Parse(args); err != nil || len(flags.Args()) != 0 {
		return "", "", errors.New("invalid_arguments")
	}
	return address, *config, nil
}
