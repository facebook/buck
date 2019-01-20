package main

import "fmt"
import "io/ioutil"

func main() {
	data, err := ioutil.ReadFile("files/test.txt")
	if err != nil {
		panic(err)
	}

	fmt.Println(string(data))
}
