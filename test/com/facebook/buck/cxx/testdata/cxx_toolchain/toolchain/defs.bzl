def define_toolchain(**kwargs):
    native.cxx_toolchain(
        archiver_type = "bsd",
        compiler_type = "clang",
        linker_type = "gnu",
        shared_library_interface_type = "enabled",
        object_file_extension = "object",
        static_library_extension = "static",
        shared_library_extension = "so",
        shared_library_versioned_extension_format = "%s.shared",
        use_header_map = True,
        visibility = ["PUBLIC"],
        **kwargs
    )
