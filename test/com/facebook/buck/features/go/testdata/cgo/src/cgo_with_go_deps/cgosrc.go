package cgo

import (
	"foo"
)

/*
#include "src/cxx/lib.h"
*/
import "C"

import (
	"fmt"
)

func Test() {
	foo.Foo()
	Bar()

	cs := C.CString("Go string")
	csRet := C.complex_func(cs)
	fmt.Printf("fmt: %s\n", C.GoString(csRet))
}
