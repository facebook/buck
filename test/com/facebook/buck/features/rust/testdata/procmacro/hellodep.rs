pub fn hellodep() -> String {
    println!("I'm in a procmacro as a dependency");
    String::from("hello!")
}
