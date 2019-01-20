package somepkg

/*
 #include <stdlib.h>
*/
import "C"
import (
	"fmt"
)

func Random() int {
	return int(C.random())
}
func Test() {
	Bar()
	fmt.Println(Random())
}
