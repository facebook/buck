package lib

import "cgo"

func Quad(i int) int {
	return cgo.Double(cgo.Double(i))
}
