mod generated;

fn main() {
    println!("info: {}", generated::INFO);
}

#[test]
fn test_submod() {
    assert_eq!(generated::INFO, "this is generated info");
}
