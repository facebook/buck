""" Module docstring """

def _add_impl(ctx):
    ctx.actions.args().add(1).add("--foo", "bar")

def _add_all_impl(ctx):
    ctx.actions.args().add(1).add_all([2, "--foo", "bar"])

def _add_failure_impl(ctx):
    ctx.actions.args().add([])

def _add_all_failure_impl(ctx):
    ctx.actions.args().add_all([{}])

def _add_args_failure_impl(ctx):
    ctx.actions.args().add(ctx.actions.args())

def _add_all_args_failure_impl(ctx):
    ctx.actions.args().add_all([ctx.actions.args()])

def _init_impl(ctx):
    ctx.actions.args(2)
    ctx.actions.args("--foo")

def _init_failure_impl(ctx):
    ctx.actions.args({})

def _init_list_impl(ctx):
    ctx.actions.args([2, "--foo", "bar"])

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
