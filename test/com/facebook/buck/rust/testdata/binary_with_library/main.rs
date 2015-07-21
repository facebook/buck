extern crate messenger;

fn main() {
    let messenger = messenger::Messenger::new("Hello, world!");
    messenger.deliver();
}
