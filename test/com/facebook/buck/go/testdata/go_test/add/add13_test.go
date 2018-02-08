package add

import "testing"

func TestAdd13(t *testing.T) {
	if Add13(3) != 16 {
		t.Error("Wrong!")
	} else {
		t.Log("Success!")
	}
}
