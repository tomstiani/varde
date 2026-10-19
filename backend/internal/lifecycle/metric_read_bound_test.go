package lifecycle_test

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/tomstiani/varde/backend/internal/httpapi"
	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func TestMetricDetailReadBoundPreservesOriginalEventTimestampGrammar(t *testing.T) {
	store, err := lifecycle.Open(filepath.Join(t.TempDir(), "varde.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	credential := func(role lifecycle.Role) string {
		identity, err := store.CreateIdentity(context.Background(), role, "synthetic")
		if err != nil {
			t.Fatal(err)
		}
		credential, err := store.IssueCredential(context.Background(), identity.ID)
		if err != nil {
			t.Fatal(err)
		}
		return credential.Secret
	}
	sender, reader := credential(lifecycle.Sender), credential(lifecycle.Installation)
	label := strings.Repeat("<", 128)
	metadata := `"source":"` + label + `","resource":"` + label + `","collector":"` + label + `"`
	entry := `{"type":"measurement","label":"` + label + `","unit":"` + strings.Repeat("<", 32) + `","value":83.00,` + metadata + `}`
	body := `{"schemaVersion":1,"eventId":"` + strings.Repeat("<", 256) + `","title":"` + strings.Repeat("<", 256) + `","summary":"` + strings.Repeat("<", 8192) + `","priority":"high","observedAt":"2026-10-19T12:00:00.FRACTIONZ","dataDefaults":{` + metadata + `},"data":[` + strings.TrimSuffix(strings.Repeat(entry+",", 8), ",") + `]}`
	body = strings.Replace(body, "FRACTION", strings.Repeat("0", lifecycle.MaxBodyBytes-len(body)+len("FRACTION")), 1)
	accepted, _, err := store.Submit(context.Background(), sender, []byte(body))
	if err != nil {
		t.Fatal(err)
	}
	answers := map[string]string{"summary": "omit"}
	for _, id := range []string{"d0.metric", "d1.metric", "d2.metric", "d3.metric", "d4.metric", "d5.metric", "d6.metric", "d7.metric"} {
		answers[id] = "p0"
	}
	if _, err = store.CompleteSelectionFixture(context.Background(), answers); err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(httpapi.New(store))
	defer server.Close()
	request, err := http.NewRequest("GET", server.URL+"/v1/alerts/"+accepted.ID, nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+reader)
	response, err := server.Client().Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	encoded, err := io.ReadAll(response.Body)
	if err != nil || response.StatusCode != 200 || len(encoded) > 131072 {
		t.Fatalf("detail read exceeded contract: status=%d bytes=%d error=%v", response.StatusCode, len(encoded), err)
	}
	read, err := store.Read(context.Background(), reader, accepted.ID)
	if err != nil || read.Original.ObservedAt != accepted.Original.ObservedAt || read.Original.Summary != accepted.Original.Summary {
		t.Fatal("original content narrowed or rewritten")
	}
}
