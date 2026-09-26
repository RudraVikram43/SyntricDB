package syntricdb

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
)

type transport struct {
	baseURL    string
	authHeader string
	client     *http.Client
}

func newTransport(cfg *Config) *transport {
	raw := cfg.User + ":" + cfg.Password
	return &transport{
		baseURL:    fmt.Sprintf("http://%s:%d", cfg.Host, cfg.Port),
		authHeader: "Basic " + base64.StdEncoding.EncodeToString([]byte(raw)),
		client:     &http.Client{},
	}
}

// response is the top-level API response, kept as raw JSON per field so the
// caller can decode "data" (an array of row objects) preserving each row's
// column order — something map[string]any can't do, since Go map iteration
// order is randomized.
type response map[string]json.RawMessage

func (r response) bool(key string) bool {
	var v bool
	if raw, ok := r[key]; ok {
		_ = json.Unmarshal(raw, &v)
	}
	return v
}

func (r response) string(key string) string {
	var v string
	if raw, ok := r[key]; ok {
		_ = json.Unmarshal(raw, &v)
	}
	return v
}

func (r response) float(key string) (float64, bool) {
	raw, ok := r[key]
	if !ok {
		return 0, false
	}
	var v float64
	if err := json.Unmarshal(raw, &v); err != nil {
		return 0, false
	}
	return v, true
}

func (r response) rawArray(key string) ([]json.RawMessage, bool) {
	raw, ok := r[key]
	if !ok {
		return nil, false
	}
	var arr []json.RawMessage
	if err := json.Unmarshal(raw, &arr); err != nil {
		return nil, false
	}
	return arr, true
}

func (t *transport) do(ctx context.Context, method, path string, body map[string]any) (response, error) {
	var reader io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		reader = bytes.NewReader(b)
	}

	req, err := http.NewRequestWithContext(ctx, method, t.baseURL+path, reader)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", t.authHeader)
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json; charset=UTF-8")
	}

	resp, err := t.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("syntricdb: could not reach server at %s: %w", t.baseURL, err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	var payload response
	if len(respBody) > 0 {
		_ = json.Unmarshal(respBody, &payload) // malformed body -> empty payload, status code still checked below
	}

	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
		return nil, &Error{Message: payload.errorOr(fmt.Sprintf("HTTP %d", resp.StatusCode)), Auth: true}
	}
	if resp.StatusCode >= 400 {
		return nil, &Error{Message: payload.errorOr(fmt.Sprintf("HTTP %d", resp.StatusCode))}
	}
	return payload, nil
}

func (r response) errorOr(fallback string) string {
	if r == nil {
		return fallback
	}
	if s := r.string("error"); s != "" {
		return s
	}
	return fallback
}

// decodeOrderedRow decodes a JSON object's keys in the order they appear in
// the source bytes, since a plain map[string]any would randomize them.
func decodeOrderedRow(raw json.RawMessage) ([]string, []any, error) {
	dec := json.NewDecoder(bytes.NewReader(raw))
	tok, err := dec.Token()
	if err != nil {
		return nil, nil, err
	}
	if delim, ok := tok.(json.Delim); !ok || delim != '{' {
		return nil, nil, fmt.Errorf("syntricdb: expected a JSON object for a result row")
	}
	var keys []string
	var values []any
	for dec.More() {
		keyTok, err := dec.Token()
		if err != nil {
			return nil, nil, err
		}
		key, _ := keyTok.(string)
		var val any
		if err := dec.Decode(&val); err != nil {
			return nil, nil, err
		}
		keys = append(keys, key)
		values = append(values, val)
	}
	return keys, values, nil
}
