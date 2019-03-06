extern crate common;

pub fn do_thing() {
  inner::innerdo();
}

mod inner {
  pub fn innerdo() {
    common::common();
  }
}
