package embedded

import (
	"bytes"
	"encoding/binary"
	"testing"
)

func TestEmbeddedPayloadFooterBounds(t *testing.T) {
	payload := []byte("platform bundle payload")
	data := append([]byte("native installer"), payload...)
	data = append(data, []byte(selfExtractMagic)...)
	footer := make([]byte, 8)
	binary.BigEndian.PutUint64(footer, uint64(len(payload)))
	data = append(data, footer...)

	offset, size, err := readEmbeddedPayloadBounds(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(data[offset:offset+size], payload) {
		t.Fatalf("embedded payload mismatch: got %q, want %q", data[offset:offset+size], payload)
	}
}
