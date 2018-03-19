package lib_test

import (
    "io/ioutil"
    "testing"
)

func TestResource(t *testing.T) {
    out, err := ioutil.ReadFile("testdata/input")
    if err != nil {
        t.Error("Could not read file 'testdata/input':", err)
    }

    if string(out) != "hello\n" {
        t.Errorf("Bad data read - expected 'hello\n', go %v", string(out))
    }
}
