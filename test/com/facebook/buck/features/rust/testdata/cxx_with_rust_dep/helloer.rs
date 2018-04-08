extern crate morehello;

#[no_mangle]
pub extern fn helloer() {
    println!("I'm printing hello!");
    morehello::helloer();
}
