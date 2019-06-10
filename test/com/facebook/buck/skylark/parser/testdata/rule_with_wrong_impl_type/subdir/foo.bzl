""" Module docstring """

some_rule = rule(
    attrs = {
        "attr1": attr.int(
            default = 2,
            mandatory = False,
        ),
    },
    implementation = "test",
)
