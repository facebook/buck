package main

import "messenger"

func main() {
	messenger := messenger.NewMessenger("Hello, world!")
	messenger.Deliver()
}
