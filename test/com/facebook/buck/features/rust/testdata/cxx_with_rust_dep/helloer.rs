extern crate morehello;

#[no_mangle]
pub extern "C" fn helloer() {
    println!("I'm printing hello!");
    morehello::helloer();
}
