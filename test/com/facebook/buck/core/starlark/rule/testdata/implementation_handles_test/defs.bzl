""" Module docstring """

bat_script = """
@echo off

echo Message on stdout

for %%a in (%*) do (
   echo arg[%%a]
)
echo CUSTOM_ENV: %CUSTOM_ENV%

if "%EXIT_CODE%"=="" (exit /B 0)
exit /B %EXIT_CODE%
"""

sh_script = """
#!/bin/bash

echo "Message on stdout"
for arg in "$@"; do
  echo "arg[${arg}]"
done
echo "CUSTOM_ENV: $CUSTOM_ENV"

if [ -n "$EXIT_CODE" ]; then
  exit "$EXIT_CODE"
fi
exit 0
"""

def _impl(ctx):
    out = ctx.actions.declare_file(ctx.attr.script)
    ctx.actions.write(out, ctx.attr.contents, is_executable = True)
    ret = []
    if ctx.attr.default_info:
        ret.append(DefaultInfo(default_outputs = [out]))
    if ctx.attr.run_info:
        ret.append(
            RunInfo(
                env = {"CUSTOM_ENV": "CUSTOM", "EXIT_CODE": str(ctx.attr.exit_code)},
                args = [out, "rulearg1"],
            ),
        )
    if ctx.attr.test_info:
        ret.append(TestInfo(test_name = "test_rule_test", test_case_name = "main"))
    return ret

def _error_impl(ctx):
    outs = []
    out = None
    for i in range(0, ctx.attr.num_outputs):
        out = ctx.actions.declare_file("out-{}.txt".format(i))
        ctx.actions.write(out, "file {}".format(i))
        outs.append(out)
    ret = [
        DefaultInfo(default_outputs = outs),
    ]
    if ctx.attr.run_info:
        ret.append(RunInfo(args = [out]))
    if ctx.attr.test_info:
        ret.append(TestInfo(test_name = "test_error_rule", test_case_name = "main"))
    return ret

inferring_test_rule_test = rule(
    attrs = {
        "contents": attr.string(),
        "default_info": attr.bool(),
        "exit_code": attr.int(),
        "run_info": attr.bool(),
        "script": attr.string(),
        "test_info": attr.bool(),
    },
    implementation = _impl,
    infer_run_info = True,
    test = True,
)

noninferring_test_rule_test = rule(
    attrs = {
        "contents": attr.string(),
        "default_info": attr.bool(),
        "exit_code": attr.int(),
        "run_info": attr.bool(),
        "script": attr.string(),
        "test_info": attr.bool(),
    },
    implementation = _impl,
    infer_run_info = False,
    test = True,
)

test_error_rule_test = rule(
    attrs = {
        "num_outputs": attr.int(),
        "run_info": attr.bool(),
        "test_info": attr.bool(),
    },
    implementation = _error_impl,
    infer_run_info = False,
    test = True,
)

nontest_error_rule = rule(
    attrs = {
        "num_outputs": attr.int(),
        "run_info": attr.bool(),
        "test_info": attr.bool(),
    },
    implementation = _error_impl,
    infer_run_info = False,
    test = False,
)

def script_and_contents():
    if native.host_info().os.is_windows:
        script = "script.bat"
        contents = bat_script
    else:
        script = "script.sh"
        contents = sh_script
    return (script, contents)

def test_rule_wrapper(infer_run_info, **kwargs):
    script, contents = script_and_contents()
    if infer_run_info:
        inferring_test_rule_test(script = script, contents = contents, **kwargs)
    else:
        noninferring_test_rule_test(script = script, contents = contents, **kwargs)
