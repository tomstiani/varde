package lifecycle

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"testing"
)

type submissionOutcome struct {
	alert   Alert
	created bool
	err     error
}

func TestConcurrentDuplicatesAndFullBacklog(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	// Exercise independent connections, not just a single connection pool's serialization.
	var file string
	if err := f.store.db.QueryRow("SELECT file FROM pragma_database_list WHERE name='main'").Scan(&file); err != nil {
		t.Fatal(err)
	}
	second, err := Open(file)
	if err != nil {
		t.Fatal(err)
	}
	defer second.Close()
	stores := []*Lifecycle{f.store, second}

	bodies := make([]string, 20)
	for i := range bodies {
		bodies[i] = validAlert
	}
	results := submitConcurrently(stores, f.sender.Secret, bodies)
	created, id := 0, results[0].alert.ID
	for _, result := range results {
		if result.err != nil || result.alert.ID != id {
			t.Fatalf("concurrent duplicate: %+v", result)
		}
		if result.created {
			created++
		}
	}
	if created != 1 {
		t.Fatalf("created=%d", created)
	}

	for i := 1; i < MaxUnfinished-1; i++ {
		body := strings.Replace(validAlert, "nas-1", fmt.Sprintf("event-%d", i), 1)
		if _, _, err := f.store.Submit(ctx, f.sender.Secret, []byte(body)); err != nil {
			t.Fatal(err)
		}
	}
	for i := range bodies {
		bodies[i] = strings.Replace(validAlert, "nas-1", fmt.Sprintf("last-%d", i), 1)
	}
	created = 0
	for _, result := range submitConcurrently(stores, f.sender.Secret, bodies) {
		if result.created {
			created++
		} else {
			category(t, result.err, "backlog_full")
		}
	}
	if created != 1 {
		t.Fatalf("last capacity created=%d", created)
	}
	_, duplicateCreated, err := f.store.Submit(ctx, f.sender.Secret, []byte(validAlert))
	if err != nil || duplicateCreated {
		t.Fatalf("duplicate at capacity: %v %v", duplicateCreated, err)
	}
	_, _, err = f.store.Submit(ctx, f.sender.Secret, []byte(strings.Replace(validAlert, "Disk pressure", "changed", 1)))
	category(t, err, "identity_conflict")
	_, _, err = f.store.Submit(ctx, f.sender.Secret, []byte(strings.Replace(validAlert, "nas-1", "overflow", 1)))
	category(t, err, "backlog_full")

	runWorker(t, f.store)
	waitFallback(t, f.store, f.reader.Secret, id)
	_, _, err = f.store.Submit(ctx, f.sender.Secret, []byte(strings.Replace(validAlert, "nas-1", "new-capacity", 1)))
	if err != nil {
		t.Fatal("completed work did not release capacity:", err)
	}
}

func submitConcurrently(stores []*Lifecycle, token string, bodies []string) []submissionOutcome {
	outcomes := make([]submissionOutcome, len(bodies))
	start := make(chan struct{})
	var workers sync.WaitGroup
	for i, body := range bodies {
		workers.Add(1)
		go func() {
			defer workers.Done()
			<-start
			outcomes[i].alert, outcomes[i].created, outcomes[i].err = stores[i%len(stores)].Submit(context.Background(), token, []byte(body))
		}()
	}
	close(start)
	workers.Wait()
	return outcomes
}
