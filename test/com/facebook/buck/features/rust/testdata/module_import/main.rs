mod messenger;

fn main() {
    let messenger = messenger::Messenger::new("Hello, world!");
    messenger.deliver();
}
