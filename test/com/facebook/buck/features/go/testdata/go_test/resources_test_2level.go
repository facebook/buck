package lib_test

import (
	"io/ioutil"
	"testing"
)

func TestResource(t *testing.T) {
	out, err := ioutil.ReadFile("testdata/level2/input")
	if err != nil {
		t.Error("Could not read file 'testdata/level2/input':", err)
	}

	if string(out) != "hello\n" {
		t.Errorf("Bad data read - expected 'hello\n', go %v", string(out))
	}
	out, err = ioutil.ReadFile("testdata/level2bis/input")
	if err != nil {
		t.Error("Could not read file 'testdata/level2bis/input':", err)
	}

	if string(out) != "hello\n" {
		t.Errorf("Bad data read - expected 'hello\n', go %v", string(out))
	}

}
