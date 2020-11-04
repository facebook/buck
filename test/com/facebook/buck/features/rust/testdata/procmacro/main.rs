#![allow(dead_code)]

#[macro_use]
extern crate helloworld_derive;
extern crate helloworld_derive_no_extern;

trait HelloWorld {
    fn hello_world();
}

#[derive(HelloWorld)]
struct FrenchToast;

#[derive(HelloWorld)]
struct Waffles;

#[derive(HelloWorldNoExtern)]
struct WafflesNoExtern;

fn main() {
    println!("Hello");
}
