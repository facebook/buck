def _impl(ctx):
    output = ctx.actions.copy_file(ctx.attr.script, ctx.attr.script.basename)

    all_args = ctx.actions.args([output, "foo", 1, Label("//foo:bar")])
    env = {"CUSTOM_ENV": "some-string", "EXIT_CODE": str(ctx.attr.exit)}

    return [
        DefaultInfo(default_outputs = [output]),
        RunInfo(env = env, args = all_args),
        TestInfo(
            labels = ["foo", "bar", "foo"],
            contacts = ["foo@example.com", "bar@example.com", "foo@example.com"],
            test_name = "testable_rule_test",
            test_case_name = ctx.label.name,
            type = "json",
        ),
    ]

def _without_run_impl(ctx):
    output = ctx.actions.declare_file("out.txt")
    ctx.actions.write(output, "contents")
    output2 = ctx.actions.declare_file("out2.txt")
    ctx.actions.write(output2, "contents")

    return [
        DefaultInfo(default_outputs = [output, output2]),
        TestInfo(
            labels = ["foo", "bar", "foo"],
            contacts = ["foo@example.com", "bar@example.com", "foo@example.com"],
            test_name = "without_run",
            test_case_name = ctx.label.name,
        ),
    ]

testable_rule_test = rule(
    implementation = _impl,
    attrs = {
        "exit": attr.int(),
        "script": attr.source(),
    },
    test = True,
)

without_run_test = rule(
    implementation = _without_run_impl,
    attrs = {},
    test = True,
)
