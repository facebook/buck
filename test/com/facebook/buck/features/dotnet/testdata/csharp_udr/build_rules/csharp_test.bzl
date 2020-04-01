"""
Implementation of a simple C# test that can use built in csharp_library() and
prebuilt_dotnet_library() rules.

Note that by default the stdlib is not linked in.
Add 'mscorlib.dll' to `system_assemblies` to get this behavior.
"""

def _csharp_test_impl(ctx):
    # Make sure that all assemblies are copied over into the output dir that contains
    # our exe. C# requires them to all be next to eachother
    copied_assemblies = []
    for dep in ctx.attr.deps:
        assembly = dep[DotnetLibraryProviderInfo].dll
        copied_assemblies.append(ctx.actions.copy_file(assembly, assembly.basename))

    args = ctx.actions.args([
        ctx.attr._toolchain[DotnetLegacyToolchainInfo].compiler,
        "-deterministic",
        "-target:library",
        "-nostdlib",
    ])
    if ctx.attr.optimize:
        args.add("-optimize")
    args.add_all(copied_assemblies, format = "-reference:%s")
    args.add_all(ctx.attr.srcs)
    args.add_all(ctx.attr.system_assemblies, format = "-reference:%s")

    out_name = ctx.attr.out or ctx.attr.name + ".dll"
    out = ctx.actions.declare_file(out_name)

    args.add(out.as_output(), format = "-out:%s")
    ctx.actions.run([args])

    return [
        DefaultInfo(named_outputs = {"dlls": copied_assemblies}, default_outputs = [out]),
        RunInfo(env = {}, args = [ctx.attr.runner, out]),
    ]

csharp_test = rule(
    implementation = _csharp_test_impl,
    attrs = {
        "deps": attr.dep_list(
            doc = (
                "A list of C# dependencies. These will be copied to the destination " +
                "directory and linked by csc"
            ),
            providers = [DotnetLibraryProviderInfo],
        ),
        "optimize": attr.bool(doc = "Whether to pass the -optimize flag to csc"),
        "out": attr.string(doc = (
            "The name of the output file. If not provided, `name` will be used " +
            "and suffixed with .exe",
        )),
        "runner": attr.source(
            doc = (
                "A script that executes tests on the .dlls that are provided to it. " +
                "Generally a wrapper around something like vstest.console.exe"
            ),
        ),
        "srcs": attr.source_list(
            doc = "List of sources that should be compiled in the resulting .exe",
            allow_empty = False,
        ),
        "system_assemblies": attr.string_list(doc = (
            "A list of system assemblies that are required and should be linked. " +
            "e.g. \"mscorlib.dll\""
        )),
        "_toolchain": attr.dep(
            default = "//toolchains:dotnet",
            providers = [DotnetLegacyToolchainInfo],
        ),
    },
    executable = True,
    test = True,
)
