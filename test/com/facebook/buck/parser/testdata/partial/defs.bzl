def func(a, b, c = 3, d = 4, e = 5):
    return "a=%s b=%s c=%s d=%s e=%s" % (a, b, c, d, e)

def star_func(*args, **kwargs):
    return "*args=%s **kwargs=%s" % (str(args), str(sorted(kwargs.items())))

def assert_eq(expr, expected, actual):
    # python and skylark are inconsistent in use of `"` vs `'` in stringified lists/dicts. Just
    # normalize them.
    if expected.replace('"', "'") != actual.replace('"', "'"):
        fail("expected `%s == %s` but got %s" % (expr, expected, actual))

def test():
    f = native.partial(func, "a1", d = "d4")
    assert_eq('f("b2")', "a=a1 b=b2 c=3 d=d4 e=e5", f("b2", e = "e5"))

    f = native.partial(star_func, 1, 2, 3, a = "a", d = "d")
    assert_eq(
        'f(4, 5, 6, b="b", c="c")',
        "*args=(1, 2, 3, 4, 5, 6) **kwargs=[('a', 'a'), ('b', 'b'), ('c', 'c'), ('d', 'd')]",
        f(4, 5, 6, b = "b", c = "c"),
    )
