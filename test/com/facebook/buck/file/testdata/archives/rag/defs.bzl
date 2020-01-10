def _expect_path_impl(ctx):
    inputs = ctx.attr.dep[DefaultInfo].default_outputs
    if len(inputs) != 1:
        fail("Expected a single output from {}".format(ctx.attr.dep))
    for artifact in inputs:
        if artifact.basename != "some_directory":
            fail("Expected input file to be named 'some_directory'")

expect_path = rule(
    implementation = _expect_path_impl,
    attrs = {"dep": attr.dep(mandatory = True)},
)
