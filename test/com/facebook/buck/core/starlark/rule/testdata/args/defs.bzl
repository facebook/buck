""" Module docstring """

RI1 = RunInfo(env = {}, args = [1, 2, 3])
RI2 = RunInfo(env = {}, args = [4, 5, 6])
RI3 = RunInfo(env = {}, args = [7, 8, 9])

def _add_impl(ctx):
    a = ctx.actions.args().add(1).add("--foo", "bar").add(RI1).add(RI2, RI3)
    ctx.actions.write("out.txt", a)

def _add_all_impl(ctx):
    a = ctx.actions.args().add(1).add_all([2, "--foo", "bar"]).add_all([RI1, RI2, RI3])
    ctx.actions.write("out.txt", a)

def _add_format_impl(ctx):
    a = ctx.actions.args().add(1).add("--foo", "bar", format = "--prefix=%s")
    ctx.actions.write("out.txt", a)

def _add_all_format_impl(ctx):
    a = ctx.actions.args().add(1).add_all([2, "--foo", "bar"], format = "--prefix=%s")
    ctx.actions.write("out.txt", a)

def _add_failure_impl(ctx):
    ctx.actions.args().add([])

def _add_all_failure_impl(ctx):
    ctx.actions.args().add_all([{}])

def _add_args_failure_impl(ctx):
    ctx.actions.args().add(ctx.actions.args())

def _add_all_args_failure_impl(ctx):
    ctx.actions.args().add_all([ctx.actions.args()])

def _init_impl(ctx):
    a = ctx.actions.args(2).add("--foo")
    ctx.actions.write("out.txt", a)

def _init_format_impl(ctx):
    a = ctx.actions.args(2, format = "--prefix=%s").add("--foo", format = "--other=%s")
    ctx.actions.write("out.txt", a)

def _init_cliargs_impl(ctx):
    a = ctx.actions.args(RI1)
    ctx.actions.write("out.txt", a)

def _init_failure_impl(ctx):
    ctx.actions.args({})

def _init_list_impl(ctx):
    a = ctx.actions.args([RI1, 2, "--foo", "bar"])
    ctx.actions.write("out.txt", a)

def _init_list_format_impl(ctx):
    a = ctx.actions.args([2, "--foo", "bar"], format = "--prefix=%s")
    ctx.actions.write("out.txt", a)

def _init_list_failure_impl(ctx):
    ctx.actions.args([{}])

def _unbound_add_failure_impl(ctx):
    f = ctx.actions.declare_file("out.txt")
    ctx.actions.args().add(f)

def _unbound_add_all_failure_impl(ctx):
    f = ctx.actions.declare_file("out.txt")
    ctx.actions.args().add_all([f])

def _unbound_init_failure_impl(ctx):
    f = ctx.actions.declare_file("out.txt")
    ctx.actions.args(f)

def _unbound_init_list_failure_impl(ctx):
    f = ctx.actions.declare_file("out.txt")
    ctx.actions.args([f])

def _invalid_format_impl(ctx):
    if ctx.attr.type == "add":
        ctx.actions.args().add(1, format = ctx.attr.format)
    elif ctx.attr.type == "add_all":
        ctx.actions.args().add_all([1, 2], format = ctx.attr.format)
    elif ctx.attr.type == "init":
        ctx.actions.args(1, format = ctx.attr.format)
    elif ctx.attr.type == "init_list":
        ctx.actions.args([1, 2], format = ctx.attr.format)
    else:
        fail("invalid type")

invalid_format = rule(
    implementation = _invalid_format_impl,
    attrs = {"format": attr.string(), "type": attr.string()},
)

add_format = rule(
    attrs = {},
    implementation = _add_format_impl,
)

add_all_format = rule(
    attrs = {},
    implementation = _add_all_format_impl,
)

init_format = rule(
    attrs = {},
    implementation = _init_format_impl,
)

init_list_format = rule(
    attrs = {},
    implementation = _init_list_format_impl,
)

add = rule(
    attrs = {},
    implementation = _add_impl,
)

add_all = rule(
    attrs = {},
    implementation = _add_all_impl,
)

add_failure = rule(
    attrs = {},
    implementation = _add_failure_impl,
)

add_all_failure = rule(
    attrs = {},
    implementation = _add_all_failure_impl,
)

add_args_failure = rule(
    attrs = {},
    implementation = _add_args_failure_impl,
)

add_all_args_failure = rule(
    attrs = {},
    implementation = _add_all_args_failure_impl,
)

init = rule(
    attrs = {},
    implementation = _init_impl,
)

init_cliargs = rule(
    attrs = {},
    implementation = _init_cliargs_impl,
)

init_failure = rule(
    attrs = {},
    implementation = _init_failure_impl,
)

init_list = rule(
    attrs = {},
    implementation = _init_list_impl,
)

init_list_failure = rule(
    attrs = {},
    implementation = _init_list_failure_impl,
)

unbound_add_failure = rule(
    attrs = {},
    implementation = _unbound_add_failure_impl,
)
unbound_add_all_failure = rule(
    attrs = {},
    implementation = _unbound_add_all_failure_impl,
)
unbound_init_failure = rule(
    attrs = {},
    implementation = _unbound_init_failure_impl,
)
unbound_init_list_failure = rule(
    attrs = {},
    implementation = _unbound_init_list_failure_impl,
)
