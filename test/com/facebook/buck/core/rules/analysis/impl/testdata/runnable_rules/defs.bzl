def _impl(ctx):
    output = ctx.actions.copy_file(ctx.attr.script, ctx.attr.script.basename)

    all_args = ctx.actions.args([output, "foo", 1, Label("//foo:bar")])
    env = {"CUSTOM_ENV": "some-string", "EXIT_CODE": str(ctx.attr.exit)}

    return [
        DefaultInfo(default_outputs = [output]),
        RunInfo(env = env, args = all_args),
    ]

runnable_rule = rule(
    implementation = _impl,
    attrs = {
        "exit": attr.int(),
        "script": attr.source(),
    },
)
