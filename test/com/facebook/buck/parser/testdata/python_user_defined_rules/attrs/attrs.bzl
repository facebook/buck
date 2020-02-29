def _attr_rule_impl(_ctx):
    pass

attrs_with_system_defaults_rule = rule(
    implementation = _attr_rule_impl,
    attrs = {
        "param_bool": attr.bool(),
        "param_int": attr.int(),
        "param_int_list": attr.int_list(),
        "param_string": attr.string(),
        "param_string_list": attr.string_list(),
    },
)

attrs_with_defaults_rule = rule(
    implementation = _attr_rule_impl,
    attrs = {
        "param_bool": attr.bool(default = True),
        "param_dep": attr.dep(default = ":src1"),
        "param_dep_list": attr.dep_list(default = [":src1"]),
        "param_int": attr.int(default = 1),
        "param_int_list": attr.int_list(default = [1]),
        "param_output": attr.output(default = "out1.txt"),
        "param_output_list": attr.output_list(default = ["out1.txt"]),
        "param_source": attr.source(default = "src1.txt"),
        "param_source_list": attr.source_list(default = ["src1.txt"]),
        "param_string": attr.string(default = "string1"),
        "param_string_list": attr.string_list(default = ["string1"]),
    },
)
