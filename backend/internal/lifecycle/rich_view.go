package lifecycle

import (
	"encoding/json"
	"sort"
)

type Component struct {
	Type      string `json:"type"`
	DataIndex *int   `json:"dataIndex,omitempty"`
	Reading   string `json:"reading,omitempty"`
	Field     string `json:"field,omitempty"`
}

type RichView struct {
	SchemaVersion int         `json:"schemaVersion"`
	Components    []Component `json:"components"`
}

func selectRichView(candidates []Candidate, answers map[string]string) (*RichView, string) {
	if len(candidates) == 0 {
		return nil, "no_useful_data"
	}
	if len(answers) != len(candidates) {
		return nil, "invalid_output"
	}
	type selection struct {
		candidate Candidate
		band      string
	}
	selected := []selection{}
	for _, candidate := range candidates {
		answer, exists := answers[candidate.ID]
		if !exists || (answer != "omit" && (len(answer) != 2 || answer[0] != 'p' || answer[1] < '0' || answer[1] > '7')) {
			return nil, "invalid_output"
		}
		if answer != "omit" {
			selected = append(selected, selection{candidate: candidate, band: answer})
		}
	}
	if len(selected) == 0 {
		return nil, "empty_selection"
	}
	if len(selected) > 8 {
		return nil, "invalid_output"
	}
	sort.SliceStable(selected, func(i, j int) bool { return selected[i].band < selected[j].band })
	view := &RichView{SchemaVersion: 1, Components: []Component{}}
	for _, item := range selected {
		view.Components = append(view.Components, item.candidate.component())
	}
	if !validRichView(view, candidates) {
		return nil, "invalid_output"
	}
	return view, ""
}

func validRichView(view *RichView, candidates []Candidate) bool {
	if view == nil || view.SchemaVersion != 1 || len(view.Components) == 0 || len(view.Components) > 8 {
		return false
	}
	offered := map[string]bool{}
	for _, candidate := range candidates {
		encoded, _ := json.Marshal(candidate.component())
		offered[string(encoded)] = true
	}
	for _, component := range view.Components {
		encoded, err := json.Marshal(component)
		if err != nil || !offered[string(encoded)] {
			return false
		}
		delete(offered, string(encoded))
	}
	encoded, err := json.Marshal(view)
	return err == nil && len(encoded) <= 8192
}

func decodeRichView(raw []byte, original Original) *RichView {
	if len(raw) > 8192 {
		return nil
	}
	fields, err := decodeOriginalJSON(raw)
	if err != nil || len(fields) != 2 || string(fields["schemaVersion"]) != "1" {
		return nil
	}
	var components []json.RawMessage
	if json.Unmarshal(fields["components"], &components) != nil {
		return nil
	}
	for _, rawComponent := range components {
		if !validComponentJSON(rawComponent) {
			return nil
		}
	}
	var view RichView
	if json.Unmarshal(raw, &view) != nil || !validRichView(&view, prepareCandidates(original)) {
		return nil
	}
	return &view
}

func validComponentJSON(raw json.RawMessage) bool {
	var fields map[string]json.RawMessage
	if json.Unmarshal(raw, &fields) != nil {
		return false
	}
	var kind, reading, field string
	_ = json.Unmarshal(fields["type"], &kind)
	_ = json.Unmarshal(fields["reading"], &reading)
	_ = json.Unmarshal(fields["field"], &field)
	switch kind {
	case "metric":
		index := fields["dataIndex"]
		return len(fields) == 3 && reading == "value" && len(index) == 1 && index[0] >= '0' && index[0] <= '7'
	case "text":
		return len(fields) == 2 && field == "summary"
	default:
		return false
	}
}
