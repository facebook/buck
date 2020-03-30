""" Module docstring """

def _impl(_ctx):
    """ Function docstring """
    print(DefaultInfo(named_outputs = {}, default_outputs = []))

some_rule = rule(
    attrs = {},
    implementation = _impl,
)

def macro(name):
    print("in bzl: {}".format(DefaultInfo(named_outputs = {}, default_outputs = [])))
    some_rule(name = name)
