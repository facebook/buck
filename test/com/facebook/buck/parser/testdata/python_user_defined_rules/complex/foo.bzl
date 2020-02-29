load("//complex:bar.bzl", _some_rule = "some_rule")

complex_rule = _some_rule

def complex_rule_wrapper(**kwargs):
    _some_rule(**kwargs)
