pub fn do_thing() {
  crate::inner::innerdo();
}

mod inner {
  pub fn innerdo() {
    common::common();
  }
}
