package lifecycle

import (
	"context"
	"strings"
	"testing"
)

func TestMeasurementMetadataDefaultsRetainPresenceAndRejectInvalidLabels(t *testing.T) {
	for _, defaults := range []string{`null`, `{"unit":"%"}`, `{"source":" "}`, `{"source":false}`} {
		f := setup(t)
		body := strings.Replace(string(metricBody(`[{"type":"measurement","label":"CPU","value":83}]`)), `"data":`, `"dataDefaults":`+defaults+`,"data":`, 1)
		_, _, err := f.store.Submit(context.Background(), f.sender.Secret, []byte(body))
		category(t, err, "invalid_field")
	}
	for _, data := range []string{
		`[{"type":"measurement","label":"CPU","id":"same","value":1},{"type":"measurement","label":"CPU","id":"same","value":2}]`,
		`[{"type":"measurement","label":"CPU","value":1,"value":2}]`,
		`[{"type":"measurement","label":"\ud800","value":1}]`,
	} {
		f := setup(t)
		_, _, err := f.store.Submit(context.Background(), f.sender.Secret, metricBody(data))
		if err == nil {
			t.Fatalf("accepted %s", data)
		}
	}
}

func TestTypedJSONDepthIsBoundedBeforeAcceptance(t *testing.T) {
	f := setup(t)
	_, _, err := f.store.Submit(context.Background(), f.sender.Secret, metricBody(strings.Repeat("[", 9)+"0"+strings.Repeat("]", 9)))
	category(t, err, "invalid_json")
}

func TestMeasurementMetadataPresenceAndExactDecimalBounds(t *testing.T) {
	for _, token := range []string{"0", "-0.00", "1e100", "1e-100", "0e100", "1234567890123456789012345678901234"} {
		t.Run(token, func(t *testing.T) {
			f := setup(t)
			body := metricBody(`[{"type":"measurement","label":"CPU","value":` + token + `}]`)
			first, _, err := f.store.Submit(context.Background(), f.sender.Secret, body)
			if err != nil {
				t.Fatal(err)
			}
			// Explicit null and absence remain different even when both resolve to unknown.
			changed := []byte(strings.Replace(string(body), `"label":"CPU"`, `"label":"CPU","source":null`, 1))
			_, _, err = f.store.Submit(context.Background(), f.sender.Secret, changed)
			category(t, err, "identity_conflict")
			read, err := f.store.Read(context.Background(), f.reader.Secret, first.ID)
			if err != nil || !strings.Contains(string(read.Original.Data[0]), token) {
				t.Fatalf("%+v %v", read, err)
			}
		})
	}
}
