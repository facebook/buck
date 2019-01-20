package lib_test

import "lib"
import "testing"

func TestAdd2(t *testing.T) {
	if lib.BadAdd2(1) != 3 {
		t.Errorf("1 + 2 != 3")
	}
}
