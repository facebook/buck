extern crate subdir;

include!(concat!(env!("RUSTC_BUILD_CONTAINER"), "included.rs"));

mod base {
  include!(concat!(env!("RUSTC_BUILD_CONTAINER_BASE_PATH"), "included.rs"));
}

fn main() {
  println!("Got included stuff: {}", INCLUDED);
  assert_eq!(INCLUDED, base::INCLUDED);
  subdir::subdir();
}
