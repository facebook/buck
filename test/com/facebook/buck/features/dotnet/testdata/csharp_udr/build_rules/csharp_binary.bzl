load("//build_rules:csharp_binary.bzl", "csharp_compile")

"""
Implementation of a simple C# binary that can use built in csharp_library() and
prebuilt_dotnet_library() rules.

Note that by default the stdlib is not linked in.
Add 'mscorlib.dll' to `system_assemblies` to get this behavior.
"""

def _csharp_binary_impl(ctx):
    copied_artifacts, output_exe = csharp_compile(
        ctx,
        ctx.attr._toolchain[DotnetLegacyToolchainInfo],
        ctx.attr.deps,
        ctx.attr.system_assemblies,
        ctx.attr.srcs,
        ctx.attr.optimize,
        "exe",
        out_name,
        ctx.attr.main,
    )

    return [
        DefaultInfo(named_outputs = {"dlls": copied_artifacts}, default_outputs = [output_exe]),
        RunInfo(env = {}, args = [output_exe]),
    ]

csharp_binary = rule(
    implementation = _csharp_binary_impl,
    attrs = {
        "srcs": attr.source_list(
            doc = "List of sources that should be compiled in the resulting .exe",
            allow_empty = False,
        ),
        "out": attr.string(doc = (
            "The name of the output file. If not provided, `name` will be used " +
            "and suffixed with .exe",
        )),
        "main": attr.string(doc = (
            "The main entry point to the program. This should be the fully " +
            "qualified class name that contains Main. If not provided, C# will " +
            "attempt to divine this automatically. " +
            "See https://docs.microsoft.com/en-us/dotnet/csharp/language-reference/compiler-options/main-compiler-option"
        )),
        "optimize": attr.bool(doc = "Whether to pass the -optimize flag to csc"),
        "system_assemblies": attr.string_list(doc = (
            "A list of system assemblies that are required and should be linked. " +
            "e.g. \"mscorlib.dll\""
        )),
        "deps": attr.dep_list(
            doc = (
                "A list of C# dependencies. These will be copied to the destination " +
                "directory and linked by csc"
            ),
            providers = [DotnetLibraryProviderInfo],
        ),
        "_toolchain": attr.dep(
            default = "//toolchains:dotnet",
            providers = [DotnetLegacyToolchainInfo],
            docs = "The legacy_toolchain() that points to the built in dotnet toolchain",
        ),
    },
    executable = True,
)
