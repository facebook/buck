extern crate messenger;

#[cfg(whatsapp)]
extern crate whatsapp;

fn main() {
    let messenger = messenger::Messenger::new("Hello, world!");
    messenger.deliver();
    #[cfg(whatsapp)]
    {
        let wa = whatsapp::Messenger::new("New thing");
        wa.deliver();
    }
}
