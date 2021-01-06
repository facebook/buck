def _impl(_ctx):
    pass

invalid_attr = rule(
    implmentation = _impl,
    attrs = {"value": attr.int(default = "not an int")},
)
