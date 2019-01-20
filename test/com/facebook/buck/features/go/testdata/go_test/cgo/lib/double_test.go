package lib

import "testing"

func TestDouble(t *testing.T) {
	if Quad(17) == 68 {
		t.Log("Success!")
	} else {
		t.Error("Wrong answer!")
	}
}
