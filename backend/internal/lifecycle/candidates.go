package lifecycle

import (
	"encoding/json"
	"fmt"
	"strings"
)

type Candidate struct {
	ID         string  `json:"id"`
	Type       string  `json:"type"`
	DataIndex  int     `json:"dataIndex"`
	Label      string  `json:"label"`
	Source     *string `json:"source"`
	Resource   *string `json:"resource"`
	Collector  *string `json:"collector"`
	Unit       *string `json:"unit"`
	Reading    string  `json:"reading"`
	Value      string  `json:"value"`
	MeasuredAt *string `json:"measuredAt"`
	Text       string  `json:"-"`
}

func (candidate Candidate) MarshalJSON() ([]byte, error) {
	if candidate.Type == "text" {
		return json.Marshal(struct {
			ID    string `json:"id"`
			Type  string `json:"type"`
			Field string `json:"field"`
			Text  string `json:"text"`
		}{ID: candidate.ID, Type: "text", Field: "summary", Text: candidate.Text})
	}
	type descriptor Candidate
	return json.Marshal(descriptor(candidate))
}

func prepareCandidates(original Original) []Candidate {
	if validateMeasurements(&original) != nil {
		return nil
	}
	var defaults map[string]json.RawMessage
	_ = json.Unmarshal(original.DataDefaults, &defaults)
	candidates := []Candidate{}
	for index, raw := range original.Data {
		var entry map[string]json.RawMessage
		_ = json.Unmarshal(raw, &entry)
		if string(entry["value"]) == "null" {
			continue
		}
		candidates = append(candidates, Candidate{
			ID:         fmt.Sprintf("d%d.metric", index),
			Type:       "metric",
			DataIndex:  index,
			Label:      *suppliedString(entry["label"]),
			Source:     resolvedMetadata(entry, defaults, "source"),
			Resource:   resolvedMetadata(entry, defaults, "resource"),
			Collector:  resolvedMetadata(entry, defaults, "collector"),
			Unit:       suppliedString(entry["unit"]),
			Reading:    "value",
			Value:      string(entry["value"]),
			MeasuredAt: suppliedString(entry["measuredAt"]),
		})
	}
	if len(candidates) > 0 && strings.TrimSpace(original.Summary) != "" {
		candidates = append(candidates, Candidate{ID: "summary", Type: "text", Text: original.Summary})
	}
	return candidates
}

func resolvedMetadata(entry, defaults map[string]json.RawMessage, name string) *string {
	value, exists := entry[name]
	if !exists {
		value = defaults[name]
	}
	return suppliedString(value)
}

func suppliedString(raw json.RawMessage) *string {
	if len(raw) == 0 || string(raw) == "null" {
		return nil
	}
	var value string
	if json.Unmarshal(raw, &value) != nil {
		return nil
	}
	return &value
}

func (candidate Candidate) component() Component {
	if candidate.Type == "text" {
		return Component{Type: "text", Field: "summary"}
	}
	index := candidate.DataIndex
	return Component{Type: "metric", DataIndex: &index, Reading: "value"}
}
