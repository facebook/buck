extern crate common;

pub fn do_thing() {
    inner::innerdo();
}

mod inner {
    use common::common_func;

    pub fn innerdo() {
        common_func();
    }
}
