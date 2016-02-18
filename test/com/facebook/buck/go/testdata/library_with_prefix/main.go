package main

import "github.com/facebook/buck/messenger"

func main() {
	messenger := messenger.NewMessenger("Hello, world")
	messenger.Deliver()
}
