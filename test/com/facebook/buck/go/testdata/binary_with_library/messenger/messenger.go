package messenger

import "messenger/lib"

type Messenger struct {
	message string
}

func NewMessenger(message string) *Messenger {
	return &Messenger{message: message}
}

func (m *Messenger) Deliver() {
	lib.Print(m.message)
}
