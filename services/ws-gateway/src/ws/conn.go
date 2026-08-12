package ws

// conn.go — WebSocket frame reading/writing helpers (RFC 6455).
//
// D-149: minimal pure-stdlib WebSocket frame implementation. This avoids
// pulling in a third-party WebSocket library (e.g. coder/websocket) which
// would require network access to download in the offline build environment.
//
// Only the subset of RFC 6455 needed by ws-gateway is implemented:
//   - Text frames (opcode 0x1)
//   - Close frames (opcode 0x8)
//   - Ping/Pong frames (opcode 0x9/0xA)
//   - No fragmentation (messages < 64KB)
//   - No masking on server→client frames (per spec)
//   - Masking on client→server frames (per spec, verified but key not stored)

import (
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"fmt"
	"io"
)

// RFC 6455 magic GUID for the Sec-WebSocket-Accept handshake.
const wsGUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

// computeAcceptKey computes the Sec-WebSocket-Accept header value from the
// client's Sec-WebSocket-Key (RFC 6455 Section 4.2.2).
func computeAcceptKey(key string) string {
	h := sha1.New()
	h.Write([]byte(key + wsGUID))
	return base64.StdEncoding.EncodeToString(h.Sum(nil))
}

// writeFrame writes a single WebSocket frame to the writer.
// opcode: 1=text, 2=binary, 8=close, 9=ping, 10=pong.
// Server-to-client frames are NOT masked (per RFC 6455 Section 5.1).
func writeFrame(w io.Writer, opcode int, payload []byte) error {
	var header [14]byte
	header[0] = 0x80 | byte(opcode) // FIN=1 + opcode

	masked := false // server→client: no mask
	pos := 2
	payloadLen := len(payload)

	switch {
	case payloadLen < 126:
		header[1] = byte(payloadLen)
		if masked {
			header[1] |= 0x80
		}
		pos = 2
	case payloadLen < 65536:
		header[1] = 126
		if masked {
			header[1] |= 0x80
		}
		binary.BigEndian.PutUint16(header[2:4], uint16(payloadLen))
		pos = 4
	default:
		header[1] = 127
		if masked {
			header[1] |= 0x80
		}
		binary.BigEndian.PutUint64(header[2:10], uint64(payloadLen))
		pos = 10
	}

	if _, err := w.Write(header[:pos]); err != nil {
		return fmt.Errorf("ws: write header: %w", err)
	}
	if _, err := w.Write(payload); err != nil {
		return fmt.Errorf("ws: write payload: %w", err)
	}
	return nil
}

// parseFramePayload extracts the payload from a raw WebSocket frame.
// Handles client→server masking per RFC 6455 Section 5.3.
// Returns the unmasked payload, or an error if the frame is malformed.
func parseFramePayload(data []byte) ([]byte, error) {
	if len(data) < 2 {
		return nil, fmt.Errorf("ws: frame too short")
	}

	// Byte 0: FIN(1) + RSV(3) + opcode(4)
	// Byte 1: MASK(1) + payload_len(7)
	masked := data[1]&0x80 != 0
	payloadLen := int(data[1] & 0x7f)

	pos := 2
	switch payloadLen {
	case 126:
		if len(data) < 4 {
			return nil, fmt.Errorf("ws: extended payload len 16 but frame too short")
		}
		payloadLen = int(binary.BigEndian.Uint16(data[2:4]))
		pos = 4
	case 127:
		if len(data) < 10 {
			return nil, fmt.Errorf("ws: extended payload len 64 but frame too short")
		}
		payloadLen = int(binary.BigEndian.Uint64(data[2:10]))
		pos = 10
	}

	var maskKey [4]byte
	if masked {
		if len(data) < pos+4 {
			return nil, fmt.Errorf("ws: mask key missing")
		}
		copy(maskKey[:], data[pos:pos+4])
		pos += 4
	}

	if len(data) < pos+payloadLen {
		return nil, fmt.Errorf("ws: payload incomplete: have %d, need %d", len(data)-pos, payloadLen)
	}

	payload := make([]byte, payloadLen)
	copy(payload, data[pos:pos+payloadLen])

	// Unmask if necessary
	if masked {
		for i := range payload {
			payload[i] ^= maskKey[i%4]
		}
	}

	return payload, nil
}

// parseFrameOpcode extracts the opcode from a raw WebSocket frame.
func parseFrameOpcode(data []byte) (int, error) {
	if len(data) < 1 {
		return 0, fmt.Errorf("ws: empty frame")
	}
	return int(data[0] & 0x0f), nil
}
