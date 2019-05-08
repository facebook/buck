package lib

/*
// we expect to import "test.h" via -isystem flag

 #include <test.h>
*/
import "C"

func CallRemoteFunction() {
	C.remote_function()
}
