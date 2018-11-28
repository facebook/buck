package testOutput

import (
	"io/ioutil"
	"testing"
)

func TestUnprintableChars(t *testing.T) {
	dat, err := ioutil.ReadFile("file.dat")
	if err != nil {
		t.Fatal(err)
	}
	t.Errorf("%v is not printable", string(dat[:10]))
}
