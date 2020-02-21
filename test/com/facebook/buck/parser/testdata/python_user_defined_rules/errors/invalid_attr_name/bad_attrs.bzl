def _impl(_ctx):
    pass

bad_attrs = rule(implementation = _impl, attrs = {"1234isntvalid": attr.int()})
