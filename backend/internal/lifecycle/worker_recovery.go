package lifecycle

import (
	"context"
	"errors"
	"math/rand/v2"
	"time"

	"modernc.org/sqlite"
	sqlite3 "modernc.org/sqlite/lib"
)

// WorkerEvent contains only fixed categories and recovery metadata, never storage errors or originals.
type WorkerEvent struct {
	Event               string `json:"event"`
	Stage               string `json:"stage"`
	Category            string `json:"category"`
	RetryCount          uint64 `json:"retryCount"`
	ElapsedMilliseconds int64  `json:"elapsedMilliseconds"`
}

type workerRecovery struct {
	started    time.Time
	lastReport time.Time
	failures   uint64
	delay      time.Duration
}

func (r *workerRecovery) failed(events chan<- WorkerEvent) time.Duration {
	now := time.Now()
	event := ""
	if r.failures == 0 {
		r.started = now
		r.delay = time.Second
		event = "worker_recovery_started"
	} else if now.Sub(r.lastReport) >= time.Minute {
		event = "worker_recovery_waiting"
	}
	if event != "" {
		r.report(events, event, "storage_busy", r.failures)
		r.lastReport = now
	}
	r.failures++
	delay := r.delay
	r.delay = min(2*r.delay, 30*time.Second)
	return delay
}

func (r *workerRecovery) recovered(events chan<- WorkerEvent) {
	if r.failures == 0 {
		return
	}
	r.report(events, "worker_recovery_succeeded", "storage_busy", r.failures)
	*r = workerRecovery{}
}

func (r *workerRecovery) report(events chan<- WorkerEvent, event, category string, retries uint64) {
	elapsed := int64(0)
	if !r.started.IsZero() {
		elapsed = time.Since(r.started).Milliseconds()
	}
	metadata := WorkerEvent{
		Event:               event,
		Stage:               "fallback_worker",
		Category:            category,
		RetryCount:          retries,
		ElapsedMilliseconds: elapsed,
	}
	select {
	case events <- metadata:
	default:
	}
}

func isStorageBusy(err error) bool {
	var failure *sqlite.Error
	return errors.As(err, &failure) && failure.Code()&0xff == sqlite3.SQLITE_BUSY
}

func retryJitter(delay time.Duration) time.Duration {
	minimum := delay * 4 / 5
	return minimum + time.Duration(rand.Int64N(int64(delay-minimum)+1))
}

func waitForRetry(ctx context.Context, delay time.Duration) error {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}
