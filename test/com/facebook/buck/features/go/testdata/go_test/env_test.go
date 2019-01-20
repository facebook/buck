package lib_test

import "testing"
import "os"

func TestLib(t *testing.T) {
	if os.Getenv("FOO") != "BAR" {
		t.Error("FOO != BAR - wrong env value")
	}
}
