extern crate env_library;

use std::path::Path;

const FOO: Option<&str> = option_env!("FOO");
const HELLOPATH: &str = env!("HELLO");
const HELLO_EXE: &str = env!("HELLO_EXE");
static HELLO: &str = include_str!(env!("HELLO"));

fn main() {
    println!("My FOO {}", FOO.unwrap());
    println!("HELLO_EXE {}", HELLO_EXE);
    println!("HELLO {}", HELLO);
    println!("Library FOO {}", env_library::FOO);

    assert_eq!(env_library::FOO, "a simple thing");
    assert_eq!(FOO, Some("something else"));
    assert!(
        Path::new(HELLOPATH).is_absolute(),
        "HELLO {} not absolute",
        HELLOPATH
    );
    assert_eq!(HELLO, "Hello, world\n");

    assert!(
        Path::new(HELLO_EXE).is_absolute(),
        "HELLO_EXE {} not absolute",
        HELLO_EXE
    );
}

#[test]
fn test_env() {
    assert!(FOO.is_none());
    assert!(
        Path::new(HELLO_EXE).is_absolute(),
        "HELLO_EXE {} not absolute",
        HELLO_EXE
    );
    assert_eq!(env!("TEST_FOO"), "some test");
    assert_eq!(include_str!(env!("HELLO")), "Hello test world\n");
}
