package lib

import "testing"

func TestDrive(t *testing.T) {
	if Drive(1) != 2 {
		t.Errorf("1 + 1 != 2")
	}
}
