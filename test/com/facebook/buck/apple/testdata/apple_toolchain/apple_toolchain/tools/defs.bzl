def define_impl(name):
    native.python_binary(
        name = name,
        main = name + ".py",
        deps = [
            "//tools:impl",
            ":gen-sdk",
        ],
        visibility = ["PUBLIC"],
    )
