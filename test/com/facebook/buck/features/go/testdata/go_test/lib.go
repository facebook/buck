package lib

import "buck_base"

func add1(n int) int {
	return buck_base.Add(n, 1)
}

func BadAdd2(n int) int {
	return add1(n)
}