""" Module docstring """

bat_script = """
@echo off

echo Message on stdout
echo Message on stderr 1>&2

for %%a in (%*) do (
   echo arg[%%a] 1>&2
)
echo PWD: %PWD% 1>&2
echo CUSTOM_ENV: %CUSTOM_ENV% 1>&2

copy /y nul %2 2>null 1>&2

if "%EXIT_CODE%"=="" (exit /B 0)
exit /B %EXIT_CODE%
"""

sh_script = """
#!/bin/bash

if [ -z "$2" ]; then exit 10; fi

echo "Message on stdout" >&1
echo "Message on stderr" >&2
for arg in "$@"; do
  echo "arg[${arg}]" >&2
done
echo "PWD: $PWD" >&2
echo "CUSTOM_ENV: $CUSTOM_ENV" >&2

touch "$2"

if [ -n "$EXIT_CODE" ]; then
  exit "$EXIT_CODE"
fi
exit 0
"""

def _invalid_types_impl(ctx):
    t = ctx.attr.type
    if t == "arguments":
        ctx.actions.run(arguments = ["echo", []], short_name = None, env = None)
    elif t == "env":
        ctx.actions.run(arguments = ["echo"], short_name = None, env = {"foo": 1})
    elif t == "zeroargs":
        ctx.actions.run(arguments = [], short_name = None, env = None)
    else:
        fail("invalid failure type")

def _test_file_impl(ctx):
    f = ctx.actions.declare_file(ctx.attr.script_name)
    ctx.actions.write(f, ctx.attr.script, is_executable = True)

def _impl(ctx):
    f = ctx.actions.declare_file("out.txt")

    src_args = ctx.actions.args().add(f.as_output())

    executable = None
    for src in ctx.attr.srcs:
        if src.owner.name == "exe":
            executable = src
        else:
            src_args.add(src)

    if executable == None:
        fail("Expected an executable named 'exe'")

    args = ctx.actions.args(["some"]).add_all(["arg", "here"])

    env = None
    if ctx.attr.exit_code != 0 or ctx.attr.use_env:
        env = {"EXIT_CODE": str(ctx.attr.exit_code)}
        if ctx.attr.use_env:
            env["CUSTOM_ENV"] = "CUSTOM"
    ctx.actions.run(
        arguments = [executable, "--out", src_args, "--bar", args],
        env = env,
    )

my_rule = rule(
    attrs = {
        "srcs": attr.source_list(),
        "exit_code": attr.int(),
        "script": attr.string(),
        "use_env": attr.bool(),
    },
    implementation = _impl,
)

test_file = rule(
    attrs = {
        "script": attr.string(),
        "script_name": attr.string(),
    },
    implementation = _test_file_impl,
)

invalid_types = rule(
    attrs = {"type": attr.string()},
    implementation = _invalid_types_impl,
)

def test_script(**kwargs):
    if native.host_info().os.is_windows:
        script_name = "test.bat"
        script = bat_script
    else:
        script_name = "test.sh"
        script = sh_script
    test_file(script_name = script_name, script = script, **kwargs)
