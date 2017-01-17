mod ffi {
    extern {
        pub fn add(a: i32, b: i32) -> i32;
    }
}

pub fn add(a: i32, b: i32) -> i32 {
    unsafe { ffi::add(a, b) }
}
