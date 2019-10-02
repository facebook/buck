pub struct Messenger {
    message: String,
}

impl Messenger {
    pub fn new(message: &str) -> Messenger {
        Messenger { message: message.to_string(), }
    }

    pub fn deliver(&self) {
        println!("I have a message to deliver to you: {}", &self.message);
    }
}
