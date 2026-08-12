package yjs

// conn.go — minimal pure-stdlib WebSocket frame helpers (RFC 6455).
//
// D-186: These mirror ws/conn.go from the D-149 /ws handler. They are
// duplicated (rather than shared) because the originals are unexported in the
// ws package. Only the subset needed by the Yjs handler is included here:
//   - computeAcceptKey (handshake)
//   - writeFrame (server→client, binary frames, no mask)
//   - parseFramePayload (client→server, unmask)

import (
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"fmt"
	"io"
)

const wsGUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

func computeAcceptKey(key string) string {
	h := sha1.New()
	h.Write([]byte(key + wsGUID))
	return base64.StdEncoding.EncodeToString(h.Sum(nil))
}

// writeFrame writes a single WebSocket frame to the writer.
// opcode: 1=text, 2=binary, 8=close, 9=ping, 10=pong.
// Server-to-client frames are NOT masked (RFC 6455 Section 5.1).
func writeFrame(w io.Writer, opcode int, payload []byte) error {
	var header [14]byte
	header[0] = 0x80 | byte(opcode) // FIN=1 + opcode

	pos := 2
	payloadLen := len(payload)
	switch {
	case payloadLen < 126:
		header[1] = byte(payloadLen)
		pos = 2
	case payloadLen < 65536:
		header[1] = 126
		binary.BigEndian.PutUint16(header[2:4], uint16(payloadLen))
		pos = 4
	default:
		header[1] = 127
		binary.BigEndian.PutUint64(header[2:10], uint64(payloadLen))
		pos = 10
	}
	if _, err := w.Write(header[:pos]); err != nil {
		return fmt.Errorf("yjs: write header: %w", err)
	}
	if _, err := w.Write(payload); err != nil {
		return fmt.Errorf("yjs: write payload: %w", err)
	}
	return nil
}

// parseFramePayload extracts the (unmasked) payload from a raw WebSocket frame.
func parseFramePayload(data []byte) ([]byte, error) {
	if len(data) < 2 {
		return nil, fmt.Errorf("yjs: frame too short")
	}
	masked := data[1]&0x80 != 0
	payloadLen := int(data[1] & 0x7f)
	pos := 2
	switch payloadLen {
	case 126:
		if len(data) < 4 {
			return nil, fmt.Errorf("yjs: extended16 frame too short")
		}
		payloadLen = int(binary.BigEndian.Uint16(data[2:4]))
		pos = 4
	case 127:
		if len(data) < 10 {
			return nil, fmt.Errorf("yjs: extended64 frame too short")
		}
		payloadLen = int(binary.BigEndian.Uint64(data[2:10]))
		pos = 10
	}
	var maskKey [4]byte
	if masked {
		if len(data) < pos+4 {
			return nil, fmt.Errorf("yjs: mask key missing")
		}
		copy(maskKey[:], data[pos:pos+4])
		pos += 4
	}
	if len(data) < pos+payloadLen {
		return nil, fmt.Errorf("yjs: payload incomplete")
	}
	payload := make([]byte, payloadLen)
	copy(payload, data[pos:pos+payloadLen])
	if masked {
		for i := range payload {
			payload[i] ^= maskKey[i%4]
		}
	}
	return payload, nil
}
