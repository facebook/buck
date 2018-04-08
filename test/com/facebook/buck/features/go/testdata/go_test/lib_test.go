package lib

import "testing"

func TestLib(t *testing.T) {
	if add1(1) != 2 {
		t.Errorf("1 + 1 != 2")
	}
}
