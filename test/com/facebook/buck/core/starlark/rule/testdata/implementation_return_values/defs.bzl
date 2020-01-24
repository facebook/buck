""" Module docstring """

def _impl(ctx):
    f = ctx.actions.declare_file("out")
    ctx.actions.write(f, "content", is_executable = False)

    if ctx.attr.name == "can_use_providers_in_impl":
        print("in bzl: {}".format(DefaultInfo(named_outputs = {}, default_outputs = [f])))
        return None
    elif ctx.attr.name == "return_non_list":
        return 5
    elif ctx.attr.name == "return_non_info_in_list":
        return [DefaultInfo(named_outputs = {}, default_outputs = [f]), 5]
    elif ctx.attr.name == "return_duplicate_info_types":
        return [DefaultInfo(named_outputs = {}, default_outputs = [f]), DefaultInfo(named_outputs = {}, default_outputs = [f])]
    fail("unexpected rule name")

my_rule = rule(
    attrs = {},
    implementation = _impl,
)
