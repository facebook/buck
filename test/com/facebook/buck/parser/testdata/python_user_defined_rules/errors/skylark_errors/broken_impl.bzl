def _impl(_ctx):
    fail("zomg broken")

broken_impl = rule(implementation = _impl, attrs = {"content": attr.string()})
