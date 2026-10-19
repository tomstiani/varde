// Package fakepush provides an in-memory development Adapter, never external delivery.
package fakepush

import (
	"context"
	"sync"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

type Provider struct {
	mu       sync.Mutex
	results  []lifecycle.PushSendResult
	messages []lifecycle.PushMessage
}

func New(results ...lifecycle.PushSendResult) *Provider {
	return &Provider{results: append([]lifecycle.PushSendResult(nil), results...)}
}

func (p *Provider) Send(ctx context.Context, message lifecycle.PushMessage) lifecycle.PushSendResult {
	p.mu.Lock()
	defer p.mu.Unlock()
	if ctx.Err() != nil {
		return lifecycle.PushSendResult{Outcome: lifecycle.PushTransient}
	}
	p.messages = append(p.messages, message)
	if len(p.results) == 0 {
		return lifecycle.PushSendResult{Outcome: lifecycle.PushDelivered}
	}
	result := p.results[0]
	p.results = p.results[1:]
	return result
}

// Messages returns private routing data for synthetic test assertions, not diagnostics.
func (p *Provider) Messages() []lifecycle.PushMessage {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]lifecycle.PushMessage(nil), p.messages...)
}
