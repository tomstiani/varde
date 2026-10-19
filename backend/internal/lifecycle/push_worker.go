package lifecycle

import (
	"context"
	"sync"
	"time"
)

// Run dispatches at most four concurrent calls, independently of presentation work.
func (d *PushDispatcher) Run(ctx context.Context) error {
	if d.provider == nil {
		<-ctx.Done()
		return ctx.Err()
	}
	workerCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	var workers sync.WaitGroup
	stopped := make(chan error, 4)
	for range 4 {
		workers.Go(func() { stopped <- d.runPushWorker(workerCtx) })
	}
	err := <-stopped
	cancel()
	workers.Wait()
	return err
}

func (d *PushDispatcher) runPushWorker(ctx context.Context) error {
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		report, err := d.DispatchOne(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			if err := waitForRetry(ctx, time.Second); err != nil {
				return err
			}
			continue
		}
		if report.Status == "idle" || report.Status == "suppressed" {
			if err := waitForRetry(ctx, 100*time.Millisecond); err != nil {
				return err
			}
		}
	}
}
