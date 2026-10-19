package lifecycle

import (
	"context"
	"strings"
	"testing"
)

func metricBody(data string) []byte {
	return []byte(strings.Replace(validAlert, `"data":[]`, `"data":`+data, 1))
}

func TestStandaloneMeasurementRetainsExactOriginalAndDuplicateIdentity(t *testing.T) {
	f := setup(t)
	body := metricBody(`[{"type":"measurement","label":"CPU usage","value":83.00,"unit":"%","source":null,"measuredAt":"2026-10-19T12:00:00.123456789Z"}]`)
	first, created, err := f.store.Submit(context.Background(), f.sender.Secret, body)
	if err != nil || !created {
		t.Fatalf("accept: %v %v", created, err)
	}
	retry := []byte(strings.Replace(string(body), `83.00`, `8.3e1`, 1))
	duplicate, created, err := f.store.Submit(context.Background(), f.sender.Secret, retry)
	if err != nil || created || duplicate.ID != first.ID {
		t.Fatalf("duplicate: %+v %v %v", duplicate, created, err)
	}
	got, err := f.store.Read(context.Background(), f.reader.Secret, first.ID)
	if err != nil || !strings.Contains(string(got.Original.Data[0]), `83.00`) {
		t.Fatalf("original rewritten: %+v %v", got, err)
	}
	_, _, err = f.store.Submit(context.Background(), f.sender.Secret, []byte(strings.Replace(string(body), `83.00`, `84`, 1)))
	category(t, err, "identity_conflict")
}
