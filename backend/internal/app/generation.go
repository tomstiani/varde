package app

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"syscall"
	"unicode/utf8"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

const generationInvalid = "generation_configuration_invalid"

var generationIdentifier = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_-]{0,63}$`)

type generationConfiguration struct {
	configID   string
	accountRef string
	model      string
	keyFile    string
}

// Checks deliberately discard key bytes: this slice has no provider execution path.
func loadGeneration(path string) (generationConfiguration, string) {
	var config generationConfiguration
	if path == "" {
		return config, "generation_disabled"
	}
	if !filepath.IsAbs(path) {
		return config, generationInvalid
	}
	body, err := readGenerationFile(path, 8192)
	if err != nil || len(body) > 8192 || !utf8.Valid(body) {
		return config, generationInvalid
	}
	fields, ok := generationFields(body)
	if !ok || string(fields["schemaVersion"]) != "1" {
		return config, generationInvalid
	}
	var enabled bool
	if raw := fields["enabled"]; len(raw) == 0 || bytes.Equal(raw, []byte("null")) || json.Unmarshal(raw, &enabled) != nil {
		return config, generationInvalid
	}
	for name, value := range fields {
		var target *string
		switch name {
		case "schemaVersion", "enabled":
			continue
		case "configId":
			target = &config.configID
		case "accountRef":
			target = &config.accountRef
		case "model":
			target = &config.model
		case "apiKeyFile":
			target = &config.keyFile
		default:
			return generationConfiguration{}, generationInvalid
		}
		if bytes.Equal(value, []byte("null")) || json.Unmarshal(value, target) != nil || !validGenerationUnicode(value) {
			return generationConfiguration{}, generationInvalid
		}
		switch name {
		case "configId", "accountRef":
			if !generationIdentifier.MatchString(*target) {
				return generationConfiguration{}, generationInvalid
			}
		case "model":
			if *target != "jev-1.13.0" {
				return generationConfiguration{}, generationInvalid
			}
		case "apiKeyFile":
			if len(*target) > 4096 || !filepath.IsAbs(*target) || bytes.IndexByte([]byte(*target), 0) >= 0 {
				return generationConfiguration{}, generationInvalid
			}
		}
	}
	if !enabled {
		return generationConfiguration{}, "generation_disabled"
	}
	if config.configID == "" || config.accountRef == "" || config.model == "" || config.keyFile == "" {
		return generationConfiguration{}, generationInvalid
	}
	if !checkGenerationKey(config.keyFile) {
		return generationConfiguration{}, "generation_key_unavailable"
	}
	return config, "generation_ready"
}

func activateGeneration(ctx context.Context, store *lifecycle.Lifecycle, path string) (string, error) {
	config, category := loadGeneration(path)
	var manifest *lifecycle.GenerationManifest
	if category == "generation_ready" {
		manifest = &lifecycle.GenerationManifest{
			ConfigID:         config.configID,
			AccountRef:       config.accountRef,
			Model:            config.model,
			PromptRevision:   "candidate-choice-1",
			ContractVersion:  1,
			CandidateVersion: 1,
		}
	}
	activation, err := store.ConfigureGeneration(ctx, manifest)
	if err != nil {
		return "", err
	}
	if manifest != nil {
		category = activation
	}
	return category, nil
}

// encoding/json otherwise replaces lone UTF-16 surrogates in a file reference.
func validGenerationUnicode(raw []byte) bool {
	for i := 0; i < len(raw); i++ {
		if raw[i] != '\\' {
			continue
		}
		i++
		if raw[i] != 'u' {
			continue
		}
		value, _ := strconv.ParseUint(string(raw[i+1:i+5]), 16, 16)
		i += 4
		if value >= 0xdc00 && value <= 0xdfff {
			return false
		}
		if value < 0xd800 || value > 0xdbff {
			continue
		}
		if i+6 >= len(raw) || raw[i+1] != '\\' || raw[i+2] != 'u' {
			return false
		}
		low, err := strconv.ParseUint(string(raw[i+3:i+7]), 16, 16)
		if err != nil || low < 0xdc00 || low > 0xdfff {
			return false
		}
		i += 6
	}
	return true
}

// The closed grammar is a flat object of scalars; nested containers are rejected.
func generationFields(body []byte) (map[string]json.RawMessage, bool) {
	decoder := json.NewDecoder(bytes.NewReader(body))
	token, err := decoder.Token()
	if err != nil || token != json.Delim('{') {
		return nil, false
	}
	fields := make(map[string]json.RawMessage)
	for decoder.More() {
		token, err = decoder.Token()
		if err != nil {
			return nil, false
		}
		name, ok := token.(string)
		if !ok {
			return nil, false
		}
		if _, exists := fields[name]; exists {
			return nil, false
		}
		var raw json.RawMessage
		if decoder.Decode(&raw) != nil {
			return nil, false
		}
		fields[name] = raw
	}
	if token, err = decoder.Token(); err != nil || token != json.Delim('}') {
		return nil, false
	}
	if _, err = decoder.Token(); err != io.EOF {
		return nil, false
	}
	return fields, true
}

func readGenerationFile(path string, limit int64) ([]byte, error) {
	file, err := os.OpenFile(path, os.O_RDONLY|syscall.O_NONBLOCK, 0)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() {
		return nil, os.ErrInvalid
	}
	return io.ReadAll(io.LimitReader(file, limit+1))
}
