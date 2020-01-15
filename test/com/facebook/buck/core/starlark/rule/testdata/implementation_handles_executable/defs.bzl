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
    return ret

def _error_impl(ctx):
    outs = []
    out = None
    for i in range(0, ctx.attr.num_outputs):
        out = ctx.actions.declare_file("out-{}.txt".format(i))
        ctx.actions.write(out, "file {}".format(i))
        outs.append(out)
    default_info = DefaultInfo(default_outputs = outs)
    if ctx.attr.run_info:
        if out == None:
            fail("Expected at least one output")
        return [RunInfo(args = [out]), default_info]
    else:
        return [default_info]

inferring_executable_rule = rule(
    attrs = {
        "contents": attr.string(),
        "default_info": attr.bool(),
        "exit_code": attr.int(),
        "run_info": attr.bool(),
        "script": attr.string(),
    },
    implementation = _impl,
    infer_run_info = True,
)

non_inferring_executable_rule = rule(
    attrs = {
        "contents": attr.string(),
        "default_info": attr.bool(),
        "exit_code": attr.int(),
        "run_info": attr.bool(),
        "script": attr.string(),
    },
    implementation = _impl,
    infer_run_info = False,
)

inferring_error_rule = rule(
    attrs = {
        "num_outputs": attr.int(),
        "run_info": attr.bool(),
    },
    implementation = _error_impl,
    infer_run_info = True,
)

def script_and_contents():
    if native.host_info().os.is_windows:
        script = "script.bat"
        contents = bat_script
    else:
        script = "script.sh"
        contents = sh_script
    return (script, contents)

def executable_rule_wrapper(infer_run_info = False, **kwargs):
    script, contents = script_and_contents()
    if infer_run_info:
        inferring_executable_rule(script = script, contents = contents, **kwargs)
    else:
        non_inferring_executable_rule(script = script, contents = contents, **kwargs)
