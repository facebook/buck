package messenger

import "fmt"

type Messenger struct {
	message string
}

func NewMessenger(message string) *Messenger {
	return &Messenger{message: message}
}

func (m *Messenger) Deliver() {
	fmt.Print(m.message)
}
