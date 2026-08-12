package ws

import "encoding/json"

// Frame represents a WebSocket message frame.
type Frame struct {
	Type string `json:"type"`
	Data any    `json:"data,omitempty"`
}

// Encode serializes the frame to JSON bytes.
func (f Frame) Encode() ([]byte, error) {
	return json.Marshal(f)
}

// DecodeFrame deserializes JSON bytes into a Frame.
func DecodeFrame(data []byte) (Frame, error) {
	var f Frame
	err := json.Unmarshal(data, &f)
	return f, err
}
