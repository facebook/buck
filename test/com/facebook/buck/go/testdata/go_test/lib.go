package lib

func add1(n int) int {
	return n + 1
}

func BadAdd2(n int) int {
	return add1(n)
}