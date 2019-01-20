extern crate proc_macro;

use std::str::FromStr;
use proc_macro::TokenStream;

#[proc_macro_derive(HelloWorld)]
pub fn hello_world(_input: TokenStream) -> TokenStream {
  TokenStream::from_str("").unwrap() // no-op
}
