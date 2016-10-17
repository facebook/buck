extension Foo {
    public func greeting() -> String {
        return "Hello, \(name ?? "user")!"
    }
}