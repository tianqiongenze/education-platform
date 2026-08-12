package ws

import "testing"

func TestFrameEncode(t *testing.T) {
	b, err := Frame{Type: "msg", Data: "hi"}.Encode()
	if err != nil {
		t.Fatal(err)
	}
	if string(b) != `{"type":"msg","data":"hi"}` {
		t.Fatalf("got %s", b)
	}
}

func TestFrameEncodeWithoutData(t *testing.T) {
	b, err := Frame{Type: "ping"}.Encode()
	if err != nil {
		t.Fatal(err)
	}
	if string(b) != `{"type":"ping"}` {
		t.Fatalf("got %s", b)
	}
}

func TestDecodeFrame(t *testing.T) {
	f, err := DecodeFrame([]byte(`{"type":"msg","data":"hello"}`))
	if err != nil {
		t.Fatal(err)
	}
	if f.Type != "msg" {
		t.Fatalf("want msg got %s", f.Type)
	}
}

func TestDecodeFrameInvalid(t *testing.T) {
	_, err := DecodeFrame([]byte(`invalid`))
	if err == nil {
		t.Fatal("expected error for invalid json")
	}
}
