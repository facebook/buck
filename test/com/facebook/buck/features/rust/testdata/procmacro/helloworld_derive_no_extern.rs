extern crate hellodep;

use proc_macro::TokenStream;

#[proc_macro_derive(HelloWorldNoExtern)]
pub fn hello_world_no_extern(_input: TokenStream) -> TokenStream {
    println!("hellodep returned: {}", hellodep::hellodep());
    TokenStream::new() // no-op
}
