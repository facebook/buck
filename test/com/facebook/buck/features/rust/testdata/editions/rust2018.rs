pub fn do_thing() {
    crate::inner::innerdo();
}

mod inner {
    use common::common_func;

    pub fn innerdo() {
        common_func();
    }
}
