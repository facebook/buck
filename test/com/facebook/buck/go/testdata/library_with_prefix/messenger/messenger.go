package messenger

import "github.com/facebook/buck/messenger/printer"

type Messenger struct {
	message string
}

func NewMessenger(message string) *Messenger {
	return &Messenger{message: message}
}

func (m *Messenger) Deliver() {
	printer.Print(m.message)
}
