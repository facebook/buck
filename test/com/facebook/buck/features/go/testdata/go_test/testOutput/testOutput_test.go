package testOutput

import (
	"encoding/hex"
	"testing"
)

func TestUnprintableChars(t *testing.T) {
	dat, err := hex.DecodeString("ffd8ffe000104a464946")
	if err != nil {
		t.Fatal(err)
	}
	t.Errorf("%v is not printable", string(dat))
}
