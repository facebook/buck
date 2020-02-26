def csharp_compile(ctx, toolchain, deps, assemblies, system_assemblies, srcs, optimize, target_type, out_name, main):
    """
    Take a number of inputs and compile them with csc.exe, putting them all one dir

    Attrs:
        ctx: A standard rule context used to declare actions
        toolchain: A DotnetLibraryProviderInfo instance describing the .NET toolchain
        deps: A list of DotnetLibraryProviderInfo dependencies
        assemblies: A list of Artifacts of assemblies that should be linked
        system_assemblies: A list of names of dlls to link against the system library
        srcs: A list of sources (must have at least one) that should be used in this
              compile step
        optimize: Whether or not the optimize flag should be passed to csc
        target_type: The type to use with the -target csc option
        out_name: The name of the output file
        main: If provided, the main entry point, passed to csc's -main

    Returns:
        (List[Artifact], Artifact): The list of copied deps/assemblies, and the
                                    final .dll or .exe
    """

    # Make sure that all assemblies are copied over into the output dir that contains
    # our exe. C# requires them to all be next to eachother at execution time
    copied_assemblies = []
    for dep in deps:
        assembly = dep[DotnetLibraryProviderInfo].dll
        copied_assemblies.append(ctx.actions.copy_file(assembly, assembly.basename))
    for assembly in assemblies:
        copied_assemblies.append(ctx.actions.copy_file(assembly, assembly.basename))

    args = ctx.actions.args([
        toolchain.compiler,
        "-deterministic",
        "-target:" + target_type,
        "-nostdlib",
    ])
    if optimize:
        args.add("-optimize")
    if main:
        args.add("-main:" + main)
    args.add_all(copied_assemblies, format = "-reference:%s")
    args.add_all(srcs)
    args.add_all(system_assemblies, format = "-reference:%s")
    out = ctx.actions.declare_file(out_name)

    args.add(out.as_output(), format = "-out:%s")
    ctx.actions.run([args])

    return (copied_assemblies, out)
