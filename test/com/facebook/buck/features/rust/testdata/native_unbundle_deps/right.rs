extern crate bottom;

#[no_mangle]
pub unsafe extern "C" fn foo_right() {
    bottom::bar();
}
