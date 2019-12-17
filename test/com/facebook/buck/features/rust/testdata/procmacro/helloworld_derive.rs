extern crate hellodep;
extern crate proc_macro;

use proc_macro::TokenStream;
use std::str::FromStr;

#[proc_macro_derive(HelloWorld)]
pub fn hello_world(_input: TokenStream) -> TokenStream {
    println!("hellodep returned: {}", hellodep::hellodep());
    TokenStream::from_str("").unwrap() // no-op
}
