def _copy_file_impl(ctx):
    inputs = ctx.attr.dep[DefaultInfo].default_outputs
    if len(inputs) != 1:
        fail("Expected a single output from {}".format(ctx.attr.dep))
    for artifact in inputs:
        ctx.actions.copy_file(artifact, artifact.basename)

    if ctx.attr.exe:
        if ctx.attr.dep[RunInfo] == None:
            fail("Expected run info")

copy_file = rule(
    implementation = _copy_file_impl,
    attrs = {"dep": attr.dep(mandatory = True), "exe": attr.bool()},
)
