package cgo

// #include "call_go.h"
import "C"

func PrintSomethingFromGo() {
	C.printSomethingFromGo(1, 2)
}
