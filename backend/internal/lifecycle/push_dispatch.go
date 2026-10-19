package lifecycle

import (
	"context"
	"time"
)

// PushEnvelope contains hints, never original alert content or network locations.
type PushEnvelope struct {
	Version        string `json:"version"`
	RegistrationID string `json:"registrationId"`
	AlertID        string `json:"alertId"`
}

type PushMessage struct {
	Provider    string
	Destination string
	Data        PushEnvelope
}

type PushOutcome string

const (
	PushDelivered     PushOutcome = "delivered"
	PushTransient     PushOutcome = "transient"
	PushPermanent     PushOutcome = "permanent_destination"
	PushConfiguration PushOutcome = "configuration"
)

type PushSendResult struct {
	Outcome    PushOutcome
	RetryAfter time.Duration
}

// PushProvider must honor context cancellation and perform no hidden retries.
type PushProvider interface {
	Send(context.Context, PushMessage) PushSendResult
}

type PushDispatchOptions struct {
	Now         func() time.Time
	RetryJitter func(time.Duration) time.Duration
	Timeout     time.Duration
}
type PushDispatchReport struct{ Status string }
type PushDispatcher struct {
	store    *Lifecycle
	provider PushProvider
	now      func() time.Time
	jitter   func(time.Duration) time.Duration
	timeout  time.Duration
}

func (s *Lifecycle) NewPushDispatcher(provider PushProvider, options PushDispatchOptions) *PushDispatcher {
	if options.Now == nil {
		options.Now = time.Now
	}
	if options.RetryJitter == nil {
		options.RetryJitter = retryJitter
	}
	if options.Timeout <= 0 || options.Timeout > 30*time.Second {
		options.Timeout = 10 * time.Second
	}
	return &PushDispatcher{store: s, provider: provider, now: options.Now, jitter: options.RetryJitter, timeout: options.Timeout}
}

func (d *PushDispatcher) retryDelay(attempt int, providerWait time.Duration) time.Duration {
	delay := []time.Duration{5 * time.Second, 30 * time.Second, 2 * time.Minute}[min(max(attempt, 1), 3)-1]
	delay = max(delay*4/5, min(d.jitter(delay), delay*6/5))
	return max(delay, max(providerWait, 0))
}

func (d *PushDispatcher) DispatchOne(ctx context.Context) (PushDispatchReport, error) {
	if d.provider == nil {
		return PushDispatchReport{Status: "not_configured"}, nil
	}
	reservation, found, err := d.reserve(ctx)
	if err != nil {
		return PushDispatchReport{}, safe(err)
	}
	if !found {
		return PushDispatchReport{Status: "idle"}, nil
	}
	current, err := d.current(ctx, reservation)
	if err != nil {
		return PushDispatchReport{}, safe(err)
	}
	if !current {
		return PushDispatchReport{Status: "suppressed"}, nil
	}
	sendCtx, cancel := context.WithTimeout(ctx, d.timeout)
	defer cancel()
	result := d.provider.Send(sendCtx, reservation.message)
	status, err := d.complete(ctx, reservation, result)
	if err != nil {
		return PushDispatchReport{}, safe(err)
	}
	return PushDispatchReport{Status: status}, nil
}
