def next(collection):
    for x in collection:
        return x
    fail("Collection was empty")

def _write_string_impl(ctx):
    out = ctx.actions.declare_file("out.txt")
    ctx.actions.write(out, ctx.attr.contents)

def _copy_string_impl(ctx):
    in_file = next(ctx.attr.dep[DefaultInfo].default_outputs)
    out = ctx.actions.copy_file(in_file, "out_string.txt")
    if out.basename != "out_string.txt":
        fail("Expected file to be declared with name out_string.txt")

def _copy_artifact_impl(ctx):
    in_file = next(ctx.attr.dep[DefaultInfo].default_outputs)
    out_file = ctx.actions.declare_file("out_artifact.txt")
    out = ctx.actions.copy_file(in_file, out_file)
    if out != out_file:
        fail("Expected {} to equal {}".format(out, out_file))

write_string = rule(implementation = _write_string_impl, attrs = {"contents": attr.string()})

copy_string = rule(implementation = _copy_string_impl, attrs = {"dep": attr.dep()})

copy_artifact = rule(implementation = _copy_artifact_impl, attrs = {"dep": attr.dep()})
