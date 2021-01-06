def _dummy_csharp_impl(ctx):
    out = ctx.actions.declare_file("out.txt")
    ctx.actions.run(arguments = [
        ctx.attr.toolchain[DotnetLegacyToolchainInfo].compiler,
        ctx.attr.src,
        out.as_output(),
    ])

dummy_csharp = rule(
    implementation = _dummy_csharp_impl,
    attrs = {
        "src": attr.source(),
        "toolchain": attr.dep(),
    },
)
