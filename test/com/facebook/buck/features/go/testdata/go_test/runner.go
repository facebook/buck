package main

import (
  "fmt"
  "io"
  "os"
	"regexp"
  "testing"
  _test "lib"
)

var tests = []testing.InternalTest{

	{"TestLib", _test.TestLib},
  {"TestResource", _test.TestResource},

}

var benchmarks = []testing.InternalBenchmark{
}
var examples = []testing.InternalExample{
}


type testDeps struct{}

func (testDeps) MatchString(pat, str string) (result bool, err error) {
  var matchPat string
  var matchRe *regexp.Regexp
	if matchRe == nil || matchPat != pat {
		matchPat = pat
		matchRe, err = regexp.Compile(matchPat)
		if err != nil {
			return
		}
	}
	return matchRe.MatchString(str), nil
}

func (testDeps) StartCPUProfile(w io.Writer) error {
	return nil
}

func (testDeps) StopCPUProfile() {}

func (testDeps) WriteHeapProfile(w io.Writer) error {
	return nil
}

func (testDeps) WriteProfileTo(name string, w io.Writer, debug int) error {
	return nil
}

func (testDeps) ImportPath() string {
	return "lib"
}

func (testDeps) StartTestLog(w io.Writer) {}

func (testDeps) StopTestLog() error {return nil}

func main() {

	fmt.Printf("Hello, world!\n")

	m := testing.MainStart(testDeps{}, tests, benchmarks, examples)

  os.Exit(m.Run())
}