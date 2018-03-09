package lib

/*
int multiply2(int i) {
	return i << 1;
}
*/
import "C"

func Double(i int) int {
	return int(C.multiply2(C.int(i)))
}
