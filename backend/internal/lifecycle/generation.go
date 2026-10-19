package lifecycle

import (
	"context"
	"database/sql"
	"errors"
)

// GenerationManifest contains disclosure semantics, never a secret or secret reference.
type GenerationManifest struct {
	ConfigID         string
	AccountRef       string
	Model            string
	PromptRevision   string
	ContractVersion  int
	CandidateVersion int
}

// ConfigureGeneration records restart activation before workers start. This does
// not enable execution: all work still uses the existing no-model fallback.
func (s *Lifecycle) ConfigureGeneration(ctx context.Context, selected *GenerationManifest) (string, error) {
	category := "generation_disabled"
	err := s.transaction(ctx, func(conn *sql.Conn) error {
		var active any
		if selected != nil {
			var retained GenerationManifest
			err := conn.QueryRowContext(ctx, `
				SELECT config_id, account_ref, model, prompt_revision, contract_version, candidate_version
				FROM generation_manifests WHERE config_id=?`, selected.ConfigID).Scan(
				&retained.ConfigID,
				&retained.AccountRef,
				&retained.Model,
				&retained.PromptRevision,
				&retained.ContractVersion,
				&retained.CandidateVersion,
			)
			switch {
			case errors.Is(err, sql.ErrNoRows):
				if _, err = conn.ExecContext(ctx, `
					INSERT INTO generation_manifests(
						config_id, account_ref, model, prompt_revision, contract_version, candidate_version
					) VALUES(?,?,?,?,?,?)`,
					selected.ConfigID,
					selected.AccountRef,
					selected.Model,
					selected.PromptRevision,
					selected.ContractVersion,
					selected.CandidateVersion,
				); err != nil {
					return err
				}
				category = "generation_ready"
				active = selected.ConfigID
			case err != nil:
				return err
			case retained != *selected:
				category = "generation_configuration_invalid"
			default:
				category = "generation_ready"
				active = selected.ConfigID
			}
		}
		_, err := conn.ExecContext(ctx, `UPDATE generation_activation SET config_id=? WHERE singleton=1`, active)
		return err
	})
	if err != nil {
		return "", safe(err)
	}
	return category, nil
}
