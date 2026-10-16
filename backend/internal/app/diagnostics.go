package app

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"time"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

// ReportFailure accepts a sanitized Run error and cannot let stderr backpressure prevent exit.
func ReportFailure(writer io.Writer, failure error) {
	done := make(chan struct{})
	go func() {
		defer close(done)
		_, _ = fmt.Fprintln(writer, "varde:", failure)
	}()
	timer := time.NewTimer(100 * time.Millisecond)
	defer timer.Stop()
	select {
	case <-done:
	case <-timer.C:
	}
}

func workerDiagnostics(writer io.Writer) (chan lifecycle.WorkerEvent, func(context.Context)) {
	events := make(chan lifecycle.WorkerEvent, 16)
	done := make(chan struct{})
	if writer == nil {
		writer = io.Discard
	}
	go func() {
		defer close(done)
		encoder := json.NewEncoder(writer)
		for event := range events {
			if err := encoder.Encode(event); err != nil {
				return
			}
		}
	}()
	return events, func(ctx context.Context) {
		close(events)
		timer := time.NewTimer(100 * time.Millisecond)
		defer timer.Stop()
		// An arbitrary io.Writer cannot be interrupted; never await it indefinitely.
		select {
		case <-done:
		case <-ctx.Done():
		case <-timer.C:
		}
	}
}
