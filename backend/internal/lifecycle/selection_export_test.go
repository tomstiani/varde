package lifecycle

import "context"

// CompleteSelectionFixture exists only in test binaries; no runtime selection override is exported.
func (s *Lifecycle) CompleteSelectionFixture(ctx context.Context, answers map[string]string) (bool, error) {
	return s.completeNextSelection(ctx, func([]Candidate) map[string]string { return answers })
}
