#![allow(dead_code)]

#[macro_use]
extern crate helloworld_derive;

trait HelloWorld {
    fn hello_world();
}

#[derive(HelloWorld)]
struct FrenchToast;

#[derive(HelloWorld)]
struct Waffles;

fn main() {
  println!("Hello");
}
