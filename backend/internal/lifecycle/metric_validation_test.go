package lifecycle

import (
	"context"
	"errors"
	"strings"
	"testing"
)

func TestStandaloneMeasurementRejectsUnsupportedOrMalformedDataBeforeAcceptance(t *testing.T) {
	cases := []struct{ data, field string }{
		{`[{"type":"measurement","label":"CPU","value":83,"samples":[]}]`, "data[0].samples"},
		{`[{"type":"records","label":"Jobs","columns":[],"rows":[]}]`, "data[0].type"},
		{`[{"type":"sequence","label":"Stages","steps":[]}]`, "data[0].type"},
		{`[{"type":"measurement","label":"CPU"}]`, "data[0].value"},
		{`[{"type":"measurement","label":"CPU","value":"83"}]`, "data[0].value"},
		{`[{"type":"measurement","label":"CPU","value":1e101}]`, "data[0].value"},
		{`[{"type":"measurement","label":"CPU","value":1e-101}]`, "data[0].value"},
		{`[{"type":"measurement","label":"CPU","value":12345678901234567890123456789012345}]`, "data[0].value"},
		{`[{"type":"measurement","label":"CPU","value":1,"unit":" "}]`, "data[0].unit"},
		{`[{"type":"measurement","label":"CPU\nload","value":1}]`, "data[0].label"},
		{`[{"type":"measurement","label":"CPU","value":1,"measuredAt":"2026-10-19T12:00:00-00:00"}]`, "data[0].measuredAt"},
		{`[{"type":"measurement","label":"CPU","value":1,"measuredAt":"2026-10-19T12:00:00.1234567891Z"}]`, "data[0].measuredAt"},
		{`[{"type":"measurement","label":"CPU","value":1,"measuredAt":"2026-10-19T12:00:00+24:00"}]`, "data[0].measuredAt"},
		{`[{"type":"measurement","label":"CPU","value":1,"source":false}]`, "data[0].source"},
		{`[{"type":"measurement","label":"CPU","value":1,"PRIVATE":true}]`, "data[0]"},
	}
	for _, test := range cases {
		t.Run(test.field+test.data, func(t *testing.T) {
			f := setup(t)
			_, created, err := f.store.Submit(context.Background(), f.sender.Secret, metricBody(test.data))
			var failure *Failure
			if created || !errors.As(err, &failure) || failure.Category != "invalid_field" || failure.Field != test.field {
				t.Fatalf("%v %v", created, err)
			}
			if strings.Contains(failure.Message(), "PRIVATE") {
				t.Fatal("echoed content")
			}
			page, err := f.store.History(context.Background(), f.reader.Secret, HistoryQuery{})
			if err != nil || len(page.Items) != 0 {
				t.Fatalf("rejected original retained: %+v %v", page, err)
			}
		})
	}
}
