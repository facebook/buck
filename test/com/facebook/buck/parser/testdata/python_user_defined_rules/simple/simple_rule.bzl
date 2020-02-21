def _simple_rule_impl(ctx):
    content = "{}\n{}".format(ctx.attr.content, ctx.attr._hidden)
    ctx.actions.write("out.txt", content)

simple_rule = rule(
    implementation = _simple_rule_impl,
    attrs = {
        "content": attr.string(),
        "mandatory": attr.int(mandatory = True),
        "_hidden": attr.string(default = "hidden_var"),
    },
)

simple_rule_test = rule(
    implementation = _simple_rule_impl,
    attrs = {
        "content": attr.string(),
        "mandatory": attr.int(mandatory = True),
        "_hidden": attr.string(default = "hidden_var"),
    },
    test = True,
)
