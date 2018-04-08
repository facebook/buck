include!(concat!(env!("RUSTC_BUILD_CONTAINER"), "subdir/subinclude.rs"));

mod base {
  include!(concat!(env!("RUSTC_BUILD_CONTAINER_BASE_PATH"), "subinclude.rs"));
}


pub fn subdir() {
  println!("subinclude has {}", SUBINCLUDE);
  assert_eq!(SUBINCLUDE, base::SUBINCLUDE);
}
