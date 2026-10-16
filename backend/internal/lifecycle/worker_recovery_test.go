package lifecycle

import (
	"context"
	"database/sql"
	"errors"
	"sync"
	"testing"
	"testing/synctest"

	"modernc.org/sqlite"
	"time"
)

func holdWriter(t *testing.T, store *Lifecycle) func() {
	t.Helper()
	var sequence int
	var name, path string
	if err := store.db.QueryRow("PRAGMA database_list").Scan(&sequence, &name, &path); err != nil {
		t.Fatal(err)
	}
	db, err := sql.Open("sqlite", databaseURL(path))
	if err != nil {
		t.Fatal(err)
	}
	conn, err := db.Conn(context.Background())
	if err != nil {
		db.Close()
		t.Fatal(err)
	}
	if _, err = conn.ExecContext(context.Background(), "BEGIN IMMEDIATE"); err != nil {
		conn.Close()
		db.Close()
		t.Fatal(err)
	}
	var once sync.Once
	release := func() {
		once.Do(func() { _, _ = conn.ExecContext(context.Background(), "ROLLBACK"); conn.Close(); db.Close() })
	}
	t.Cleanup(release)
	return release
}

func TestWorkerDoesNotRetryUnknownOrSameConnectionLockFailures(t *testing.T) {
	for _, fault := range []string{"unknown", "locked"} {
		t.Run(fault, func(t *testing.T) {
			f := setup(t)
			alert, _, err := f.store.Submit(context.Background(), f.sender.Secret, []byte(validAlert))
			if err != nil {
				t.Fatal(err)
			}
			attempts := 0
			f.store.beforeWorkerCommit = func(conn *sql.Conn) error {
				attempts++
				if fault == "unknown" {
					return errors.New("SENTINEL-private-error")
				}
				if _, err := conn.ExecContext(context.Background(), "CREATE TEMP TABLE lock_probe(value INTEGER); INSERT INTO lock_probe VALUES(1)"); err != nil {
					t.Fatal(err)
				}
				rows, err := conn.QueryContext(context.Background(), "SELECT value FROM lock_probe")
				if err != nil {
					t.Fatal(err)
				}
				defer rows.Close()
				if !rows.Next() {
					t.Fatal("missing lock fixture row")
				}
				_, err = conn.ExecContext(context.Background(), "DROP TABLE lock_probe")
				var locked *sqlite.Error
				if !errors.As(err, &locked) || locked.Code()&0xff != 6 {
					t.Fatalf("not a real SQLITE_LOCKED fault: %v", err)
				}
				return err
			}
			events := make(chan WorkerEvent, 2)
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			category(t, f.store.Run(ctx, events), "storage_unavailable")
			if attempts != 1 {
				t.Fatalf("fatal failure was retried: %d", attempts)
			}
			var event WorkerEvent
			select {
			case event = <-events:
			case <-time.After(time.Second):
				t.Fatal("missing fatal diagnostic")
			}
			if event.Event != "worker_stopped" || event.Stage != "fallback_worker" || event.Category != "storage_unavailable" || event.RetryCount != 0 {
				t.Fatalf("unsafe fatal diagnostic: %+v", event)
			}
			retained, err := f.store.Read(context.Background(), f.reader.Secret, alert.ID)
			if err != nil || retained.Presentation.State != "pending" || retained.Original.Summary != "Backup volume near limit" {
				t.Fatal("fatal worker failure lost or partially completed retained work", err)
			}
		})
	}
}

func TestCancellationDuringBackoffPreservesUnfinishedWork(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		f := setup(t)
		alert, _, err := f.store.Submit(context.Background(), f.sender.Secret, []byte(validAlert))
		if err != nil {
			t.Fatal(err)
		}
		if _, err = f.store.db.Exec("PRAGMA busy_timeout=0"); err != nil {
			t.Fatal(err)
		}
		release := holdWriter(t, f.store)
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		events := make(chan WorkerEvent, 1)
		done := make(chan error, 1)
		go func() { done <- f.store.Run(ctx, events) }()
		<-events
		synctest.Wait()
		started := time.Now()
		cancel()
		if err = <-done; !errors.Is(err, context.Canceled) || time.Since(started) != 0 {
			t.Fatal("backoff ignored cancellation", err)
		}
		release()
		retained, err := f.store.Read(context.Background(), f.reader.Secret, alert.ID)
		if err != nil || retained.Presentation.State != "pending" {
			t.Fatal("cancellation consumed retained work", err)
		}
		runWorker(t, f.store)
		waitFallback(t, f.store, f.reader.Secret, alert.ID)
	})
}

func TestUnconsumedRecoveryEventsDoNotBlockWorker(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		f := setup(t)
		alert, _, err := f.store.Submit(context.Background(), f.sender.Secret, []byte(validAlert))
		if err != nil {
			t.Fatal(err)
		}
		if _, err = f.store.db.Exec("PRAGMA busy_timeout=0"); err != nil {
			t.Fatal(err)
		}
		release := holdWriter(t, f.store)
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		done := make(chan error, 1)
		go func() { done <- f.store.Run(ctx, make(chan WorkerEvent)) }()
		time.Sleep(100 * time.Millisecond)
		release()
		waitFallback(t, f.store, f.reader.Secret, alert.ID)
		cancel()
		if err = <-done; !errors.Is(err, context.Canceled) {
			t.Fatal(err)
		}
	})
}

func TestReadFailsWithinTheSQLiteLockBudgetWithoutHiddenRetry(t *testing.T) {
	f := setup(t)
	release := holdWriter(t, f.store)
	started := time.Now()
	_, err := f.store.Read(context.Background(), f.reader.Secret, "unknown")
	category(t, err, "storage_unavailable")
	if elapsed := time.Since(started); elapsed > 1500*time.Millisecond {
		t.Fatalf("SQLite lock wait exceeded one second with tolerance: %v", elapsed)
	}
	release()
	_, err = f.store.Read(context.Background(), f.reader.Secret, "unknown")
	category(t, err, "not_found")
}

func TestWorkerRetriesTheWholeTransactionAfterSuccessfulCleanup(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		f := setup(t)
		alert, _, err := f.store.Submit(context.Background(), f.sender.Secret, []byte(validAlert))
		if err != nil {
			t.Fatal(err)
		}
		var sequence int
		var name, path string
		if err = f.store.db.QueryRow("PRAGMA database_list").Scan(&sequence, &name, &path); err != nil {
			t.Fatal(err)
		}
		other, err := sql.Open("sqlite", databaseURL(path))
		if err != nil {
			t.Fatal(err)
		}
		defer other.Close()
		if _, err = other.Exec("PRAGMA busy_timeout=0"); err != nil {
			t.Fatal(err)
		}
		attempts := 0
		f.store.beforeWorkerCommit = func(*sql.Conn) error {
			attempts++
			if attempts != 1 {
				return nil
			}
			_, busy := other.Exec("BEGIN IMMEDIATE")
			if busy == nil {
				_, _ = other.Exec("ROLLBACK")
				return errors.New("missing synthetic contention")
			}
			return busy
		}
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		events := make(chan WorkerEvent, 2)
		done := make(chan error, 1)
		go func() { done <- f.store.Run(ctx, events) }()
		if event := <-events; event.Event != "worker_recovery_started" {
			t.Fatal("worker did not enter recovery")
		}
		retained, err := f.store.Read(context.Background(), f.reader.Secret, alert.ID)
		if err != nil || retained.Presentation.State != "pending" || retained.Presentation.Revision != 0 {
			t.Fatal("failed transaction exposed a partial terminal result", err)
		}
		if event := <-events; event.Event != "worker_recovery_succeeded" || event.RetryCount != 1 || event.ElapsedMilliseconds < 800 || event.ElapsedMilliseconds > 1_000 {
			t.Fatalf("retry did not honor the first jittered delay: %+v", event)
		}
		completed, err := f.store.Read(context.Background(), f.reader.Secret, alert.ID)
		if err != nil || completed.Presentation.State != "fallback" || completed.Presentation.Revision != 1 || completed.Original.Summary != "Backup volume near limit" {
			t.Fatal("whole-transaction recovery changed the original or revision", err)
		}
		counts(t, f.store, 1, 1, 2)
		cancel()
		if err = <-done; !errors.Is(err, context.Canceled) {
			t.Fatal(err)
		}
	})
}

func TestWorkerStopsIfTransactionCleanupCannotBeConfirmed(t *testing.T) {
	f := setup(t)
	alert, _, err := f.store.Submit(context.Background(), f.sender.Secret, []byte(validAlert))
	if err != nil {
		t.Fatal(err)
	}
	var sequence int
	var name, path string
	if err = f.store.db.QueryRow("PRAGMA database_list").Scan(&sequence, &name, &path); err != nil {
		t.Fatal(err)
	}
	other, err := sql.Open("sqlite", databaseURL(path))
	if err != nil {
		t.Fatal(err)
	}
	defer other.Close()
	if _, err = other.Exec("PRAGMA busy_timeout=0"); err != nil {
		t.Fatal(err)
	}
	attempts := 0
	f.store.beforeWorkerCommit = func(conn *sql.Conn) error {
		attempts++
		_, busy := other.Exec("BEGIN IMMEDIATE")
		if busy == nil {
			t.Fatal("missing fault contention")
		}
		// End the transaction early so the transaction owner's cleanup fails.
		if _, err := conn.ExecContext(context.Background(), "ROLLBACK"); err != nil {
			t.Fatal(err)
		}
		return busy
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	err = f.store.Run(ctx, nil)
	category(t, err, "storage_unavailable")
	if attempts != 1 {
		t.Fatalf("retried after failed cleanup: %d", attempts)
	}
	retained, err := f.store.Read(context.Background(), f.reader.Secret, alert.ID)
	if err != nil || retained.Presentation.State != "pending" {
		t.Fatal("partial worker state escaped rollback", err)
	}
	f.store.beforeWorkerCommit = nil
	runWorker(t, f.store)
	waitFallback(t, f.store, f.reader.Secret, alert.ID)
}

func TestWorkerBacksOffForAnHourWithRateLimitedRecoveryEvents(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		f := setup(t)
		if _, err := f.store.db.Exec("PRAGMA busy_timeout=0"); err != nil {
			t.Fatal(err)
		}
		release := holdWriter(t, f.store)
		f.store.retryJitter = func(delay time.Duration) time.Duration { return delay }
		events := make(chan WorkerEvent, 128)
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		done := make(chan error, 1)
		go func() { done <- f.store.Run(ctx, events) }()
		time.Sleep(time.Hour)
		select {
		case err := <-done:
			t.Fatalf("worker stopped retrying: %v", err)
		default:
		}
		first := <-events
		if first.Event != "worker_recovery_started" || first.RetryCount != 0 {
			t.Fatalf("first event: %+v", first)
		}
		progress := <-events
		if progress.Event != "worker_recovery_waiting" || progress.ElapsedMilliseconds != 61_000 || progress.RetryCount != 6 {
			t.Fatalf("backoff schedule: %+v", progress)
		}
		last := progress
		for len(events) > 0 {
			current := <-events
			if current.ElapsedMilliseconds-last.ElapsedMilliseconds < 60_000 {
				t.Fatal("progress events exceeded one per minute")
			}
			last = current
		}
		if last.RetryCount < 100 {
			t.Fatal("retry cap or delay exceeded the agreed policy")
		}
		release()
		recovered := <-events
		if recovered.Event != "worker_recovery_succeeded" || recovered.RetryCount != 124 || recovered.ElapsedMilliseconds != 3_601_000 {
			t.Fatalf("recovery: %+v", recovered)
		}
		releaseAgain := holdWriter(t, f.store)
		restarted := <-events
		if restarted.Event != "worker_recovery_started" || restarted.RetryCount != 0 {
			t.Fatalf("recovery state was not reset: %+v", restarted)
		}
		releaseAgain()
		resetRecovery := <-events
		if resetRecovery.Event != "worker_recovery_succeeded" || resetRecovery.RetryCount != 1 || resetRecovery.ElapsedMilliseconds != 1_000 {
			t.Fatalf("backoff was not reset: %+v", resetRecovery)
		}
		cancel()
		if err := <-done; !errors.Is(err, context.Canceled) {
			t.Fatal(err)
		}
	})
}

func TestWorkerResumesRetainedWorkAfterTemporaryWriterContention(t *testing.T) {
	f := setup(t)
	alert, _, err := f.store.Submit(context.Background(), f.sender.Secret, []byte(validAlert))
	if err != nil {
		t.Fatal(err)
	}
	if _, err = f.store.db.Exec("PRAGMA busy_timeout=20"); err != nil {
		t.Fatal(err)
	}
	release := holdWriter(t, f.store)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- f.store.Run(ctx, nil) }()
	select {
	case err := <-done:
		t.Fatalf("worker exited during temporary contention: %v", err)
	case <-time.After(200 * time.Millisecond):
	}
	release()
	retained := waitFallback(t, f.store, f.reader.Secret, alert.ID)
	if retained.Original.Summary != "Backup volume near limit" || retained.Presentation.Revision != 1 {
		t.Fatal("retained work/original changed")
	}
	duplicate, created, err := f.store.Submit(context.Background(), f.sender.Secret, []byte(validAlert))
	if err != nil || created || duplicate.ID != alert.ID {
		t.Fatal("recovery changed duplicate identity")
	}
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("shutdown: %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("worker did not stop")
	}
}
