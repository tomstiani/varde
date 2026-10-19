package lifecycle

import "encoding/json"

type Role string

const (
	Sender        Role = "sender"
	Installation  Role = "installation"
	MaxBodyBytes       = 65_536
	MaxUnfinished      = 1_000
)

type Identity struct {
	ID   string `json:"id"`
	Role Role   `json:"role"`
	Name string `json:"name"`
}

type Credential struct {
	ID         string `json:"id"`
	IdentityID string `json:"identityId"`
	Secret     string `json:"secret"`
}

type Original struct {
	SchemaVersion int               `json:"schemaVersion"`
	EventID       string            `json:"eventId"`
	Title         string            `json:"title"`
	Summary       string            `json:"summary"`
	Priority      string            `json:"priority"`
	ObservedAt    string            `json:"observedAt"`
	Data          []json.RawMessage `json:"data"`
	DataDefaults  json.RawMessage   `json:"dataDefaults,omitempty"`
}

type Presentation struct {
	State    string `json:"state"`
	Revision int    `json:"revision"`
}

type Alert struct {
	ID           string       `json:"id"`
	AcceptedAt   string       `json:"acceptedAt"`
	Original     Original     `json:"original"`
	Presentation Presentation `json:"presentation"`
	RichView     *RichView    `json:"richView,omitempty"`
}
