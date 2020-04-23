def _impl(ctx):
    if type(ctx.attr.sl) != type([]):
        fail("unexpected source list type: {}".format(type(ctx.attr.sl)))

my_rule = rule(
    attrs = {
        "sl": attr.source_list(),
    },
    implementation = _impl,
)
