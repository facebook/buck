def test():
    # should be able to verify with `echo -n '123' | sha256sum`
    expected = "a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3"
    hash = native.sha256("123")
    if hash != expected:
        fail("expected `sha256(\"123\") == \"%s\" but got \"%s\" " % (expected, hash))
