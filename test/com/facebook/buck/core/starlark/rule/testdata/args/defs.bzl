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
