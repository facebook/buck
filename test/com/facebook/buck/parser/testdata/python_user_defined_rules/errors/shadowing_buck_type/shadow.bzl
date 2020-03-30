def _impl(_ctx):
    pass

shadow = rule(implementation = _impl, attrs = {"buck.type": attr.string(default = "genrule")})
