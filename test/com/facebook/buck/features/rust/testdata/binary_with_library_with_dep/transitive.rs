extern crate messenger;
extern crate messengerdep;

use messenger::Messenger;

fn main() {
    println!("Hello from transitive");

    Messenger::new("transitive").deliver();

    messengerdep::handler();
}
