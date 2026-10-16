package demo

import (
	"encoding/json"
	"errors"
	"os/exec"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func issueCredential(binary, database string, role lifecycle.Role) (lifecycle.Credential, error) {
	var identity lifecycle.Identity
	var credential lifecycle.Credential
	if err := operatorCommand(binary, database, &identity, "identity", "create", string(role), "demo"); err != nil {
		return credential, err
	}
	err := operatorCommand(binary, database, &credential, "credential", "issue", identity.ID)
	return credential, err
}

func operatorCommand(binary, database string, result any, args ...string) error {
	command := exec.Command(binary, append([]string{"-db", database}, args...)...)
	body, err := command.Output()
	if err != nil {
		return errors.New("operator command failed")
	}
	if err = json.Unmarshal(body, result); err != nil {
		return errors.New("invalid operator response")
	}
	return nil
}
