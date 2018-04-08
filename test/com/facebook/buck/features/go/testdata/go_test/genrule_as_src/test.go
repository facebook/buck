package main

import "testing"

func TestGenRuleSource(t *testing.T) {
	if testSource() != "source_included" {
		t.Fatalf("wrong output")
	}
}
