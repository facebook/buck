package lib_test

import "testing"
import "os"
import "path/filepath"

func TestLib(t *testing.T) {
	if os.Getenv("FOO") != "BAR" {
		t.Error("FOO != BAR - wrong env value")
	}
	// These env vars use bucks location/exe macros
	env_macros := []string{"AS_EXE", "AS_LOC"}
	for _, varname := range env_macros {
		value := os.Getenv(varname)
		if len(value) == 0 || !filepath.IsAbs(value) {
			t.Errorf("%s=[%s] unexpected value?", varname, value)
		}
	}
}
