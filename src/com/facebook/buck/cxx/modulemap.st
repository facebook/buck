module <module_name> {
    <if(use_umbrella_header)>
    umbrella header "<umbrella_header_name>"
    <else>
    umbrella "<umbrella_directory>"
    <endif>

    export *
    module * { export * }
}
