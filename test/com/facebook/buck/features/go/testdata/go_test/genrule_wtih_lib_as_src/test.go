package main

import "testing"
import "lib"

func TestGenRuleSource(t *testing.T) {
	if lib.LibFn() != "source_included" {
		t.Fatalf("wrong output")
	}
}
