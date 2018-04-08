package main

import (
	"io"
	"os"
)

func main() {
	src := os.Args[3]
	dst := os.Args[2]

	in, _ := os.Open(src)
	defer in.Close()

	out, _ := os.Create(dst)
	defer out.Close()

	io.Copy(out, in)
}
