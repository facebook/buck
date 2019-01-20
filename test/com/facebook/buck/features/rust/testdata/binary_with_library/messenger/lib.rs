pub struct Messenger {
    message: String,
}

impl Messenger {
    pub fn new(message: &str) -> Messenger {
        Messenger { message: message.to_string(), }
    }

    #[cfg(feature="warning")]
    fn unused() {}

    pub fn deliver(&self) {
        println!("I have a message to deliver to you: {}", &self.message);
    }
}
