""" Module docstring """

ContentInfo = provider(fields = [
    "path",
    "content",
    "empty",
])

def _write_output(ctx, path, ci):
    f = ctx.actions.declare_file(path)
    ctx.actions.write(f, ci.content)
    return f

def _root_rule_impl(ctx):
    root_ci = ContentInfo(path = "root", content = "root content")
    return [
        ContentInfo(content = "from_root content"),
        DefaultInfo(named_outputs = {}, default_outputs = [_write_output(ctx, "root", root_ci)]),
    ]

def _non_root_rule_impl(ctx):
    ci = ctx.attr.dep[ContentInfo]
    if ci == None:
        fail("expected ContentInfo to be available in %s", ctx.label)
    if ci.empty != None:
        fail("expected empty to be None")
    f = _write_output(ctx, ctx.label.name, ci)
    new_ci = ContentInfo(
        path = "from_{}".format(ctx.label.name),
        content = "{} + from_{} content".format(ci.content, ctx.label.name),
    )
    return [new_ci, DefaultInfo(named_outputs = {}, default_outputs = [f])]

root_rule = rule(
    attrs = {},
    implementation = _root_rule_impl,
)

non_root_rule = rule(
    attrs = {"dep": attr.dep()},
    implementation = _non_root_rule_impl,
)

def _content_info_impl(ctx):
    ret = [DefaultInfo(named_outputs = {}, default_outputs = [])]
    if ctx.attr.ci:
        ret.append(ContentInfo())
    return ret

def _single_dep_impl(ctx):
    # TODO(T48080142): Right now we have to access an attr to get its full
    #                  validation pipeline to run
    print(ctx.attr.dep)

content_info = rule(
    attrs = {"ci": attr.bool()},
    implementation = _content_info_impl,
)

does_not_require_provider = rule(
    attrs = {"dep": attr.dep()},
    implementation = _single_dep_impl,
)

requires_provider = rule(
    attrs = {"dep": attr.dep(providers = [ContentInfo])},
    implementation = _single_dep_impl,
)
