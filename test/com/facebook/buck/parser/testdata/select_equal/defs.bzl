def assert_eq(expected, actual):
    if expected != actual:
        fail("Expected {}, but got {}".format(expected, actual))

def test():
    s = select({"k": "v"})

    # Identity
    assert_eq(True, s == s)
    assert_eq(True, select_equal_internal(s, s))

    # Deep equality
    assert_eq(False, select({"1": "a"}) == select({"1": "a"}))
    assert_eq(True, select_equal_internal(select({"1": "a"}), select({"1": "a"})))

    # Unequal dictionary values
    assert_eq(False, select_equal_internal(select({"1": "a"}), select({"1": "b"})))

    # Unequal select keys
    assert_eq(False, select_equal_internal(select({"2": "a"}), select({"1": "a"})))

    # Unequal types
    assert_eq(False, select_equal_internal(select({"1": "a"}), select({"1": ["a"]})))

    # Expression identity
    expr = "x" + s
    assert_eq(True, expr == expr)
    assert_eq(True, select_equal_internal(expr, expr))

    # Expression deep equality
    assert_eq(False, "z" + select({"1": "a"}) == "z" + select({"1": "a"}))
    assert_eq(True, select_equal_internal("z" + select({"1": "a"}), "z" + select({"1": "a"})))

    # Unequal native value in expression
    assert_eq(False, select_equal_internal("2" + select({"1": "a"}), "1" + select({"1": "a"})))

    # Expression terms out of order
    assert_eq(False, select_equal_internal("2" + select({"1": "a"}), select({"1": "a"}) + "2"))

    # Unequal expression length
    expr = "2" + select({"1": "a"})
    assert_eq(False, select_equal_internal(expr, expr + "tail"))
