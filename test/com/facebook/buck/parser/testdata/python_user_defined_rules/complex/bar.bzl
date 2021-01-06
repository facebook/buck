def _some_rule_impl(_ctx):
    pass

some_rule = rule(
    implementation = _some_rule_impl,
    attrs = {
        "value": attr.string(),
    },
)

def some_rule_wrapper(**kwargs):
    some_rule(**kwargs)
