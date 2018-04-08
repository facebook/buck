extern {
    fn evenmorehello();
}

pub fn helloer() {
    println!("I'm saying \"hello\" again!");
    unsafe { evenmorehello() };
}
