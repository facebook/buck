def _simple_rule_impl(ctx):
    ctx.actions.write("out.txt", ctx.attr.content)

simple_rule = rule(
    implementation = _simple_rule_impl,
    attrs = {
        "content": attr.string(),
    },
)
