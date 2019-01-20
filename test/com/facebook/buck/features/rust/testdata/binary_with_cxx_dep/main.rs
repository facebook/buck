extern crate adder;

fn main() {
    let a = 10;
    let b = 15;
    let sum = adder::add(a, b);

    println!("{} + {} = {}", a, b, sum);
}