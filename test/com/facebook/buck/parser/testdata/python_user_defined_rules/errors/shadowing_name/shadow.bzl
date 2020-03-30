def _impl(_ctx):
    pass

shadow = rule(implementation = _impl, attrs = {"name": attr.string(default = "random")})
