""" Module docstring """

def _impl(ctx):
    f = ctx.actions.declare_file("out")
    ctx.actions.write(f, "content", is_executable = False)
    print("in bzl: {}".format(DefaultInfo(named_outputs = {}, default_outputs = [f])))

# TODO: Update when we handle and validate return values from impl

my_rule = rule(
    attrs = {},
    implementation = _impl,
)
