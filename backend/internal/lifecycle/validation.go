package lifecycle

import (
	"encoding/json"
	"fmt"
	"slices"
	"strconv"
	"strings"
	"unicode/utf8"
)

const (
	originalSchemaVersion = 1
	maxEventIDCharacters  = 256
	maxTitleCharacters    = 256
	maxSummaryCharacters  = 8192
)

var priorityValues = []string{"low", "normal", "high", "critical"}

func decodeOriginal(body []byte) (Original, error) {
	raw, err := decodeOriginalJSON(body)
	if err != nil {
		return Original{}, err
	}
	if err = validateFieldNames(raw); err != nil {
		return Original{}, err
	}
	if string(raw["schemaVersion"]) != strconv.Itoa(originalSchemaVersion) {
		return Original{}, invalidField("schemaVersion")
	}

	original := Original{SchemaVersion: originalSchemaVersion, Data: []json.RawMessage{}}
	if err = decodeStringFields(raw, &original); err != nil {
		return Original{}, err
	}
	if !slices.Contains(priorityValues, original.Priority) {
		return Original{}, invalidField("priority")
	}
	if !validTimestamp(original.ObservedAt) {
		return Original{}, invalidField("observedAt")
	}
	if data, exists := raw["data"]; exists {
		if len(data) == 0 || data[0] != '[' || json.Unmarshal(data, &original.Data) != nil {
			return Original{}, invalidField("data")
		}
	}
	if defaults, exists := raw["dataDefaults"]; exists {
		original.DataDefaults = defaults
	}
	if err := validateMeasurements(&original); err != nil {
		return Original{}, err
	}
	return original, nil
}

func validateFieldNames(raw map[string]json.RawMessage) error {
	known := map[string]bool{
		"schemaVersion": true,
		"eventId":       true,
		"title":         true,
		"summary":       true,
		"priority":      true,
		"observedAt":    true,
		"data":          true,
		"dataDefaults":  true,
	}
	for name := range raw {
		if !known[name] {
			return failure("invalid_field")
		}
	}
	return nil
}

func decodeStringFields(raw map[string]json.RawMessage, original *Original) error {
	fields := []struct {
		name          string
		destination   *string
		maxCharacters int
		nonblank      bool
	}{
		{"eventId", &original.EventID, maxEventIDCharacters, true},
		{"title", &original.Title, maxTitleCharacters, true},
		{"summary", &original.Summary, maxSummaryCharacters, false},
		{"priority", &original.Priority, 0, false},
		{"observedAt", &original.ObservedAt, 0, false},
	}
	for _, field := range fields {
		value := raw[field.name]
		if len(value) == 0 || value[0] != '"' || json.Unmarshal(value, field.destination) != nil {
			return invalidField(field.name)
		}
		if field.maxCharacters > 0 && utf8.RuneCountInString(*field.destination) > field.maxCharacters {
			return invalidField(field.name)
		}
		if field.nonblank && strings.TrimSpace(*field.destination) == "" {
			return invalidField(field.name)
		}
	}
	return nil
}

func validationMessage(field string) string {
	switch field {
	case "schemaVersion":
		return fmt.Sprintf("schemaVersion must be the integer %d.", originalSchemaVersion)
	case "eventId":
		return fmt.Sprintf("eventId must be a nonblank string of at most %s characters.", formattedLimit(maxEventIDCharacters))
	case "title":
		return fmt.Sprintf("title must be a nonblank string of at most %s characters.", formattedLimit(maxTitleCharacters))
	case "summary":
		return fmt.Sprintf("summary must be a string of at most %s characters.", formattedLimit(maxSummaryCharacters))
	case "priority":
		choices := strings.Join(priorityValues[:len(priorityValues)-1], ", ")
		return fmt.Sprintf("priority must be %s, or %s.", choices, priorityValues[len(priorityValues)-1])
	case "observedAt":
		return "observedAt must be an RFC 3339 timestamp with an explicit offset."
	case "data":
		return "data must be an array of at most 8 standalone measurements; samples, records and sequences are not supported yet."
	default:
		if strings.HasPrefix(field, "data[") || strings.HasPrefix(field, "dataDefaults") {
			return measurementValidationMessage(field)
		}
		return fmt.Sprintf("The fields must match the version %d alert schema.", originalSchemaVersion)
	}
}

func invalidField(name string) error {
	return &Failure{Category: "invalid_field", Field: name}
}
