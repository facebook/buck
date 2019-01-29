# Ok to have comments
"""Ok to have documentation string."""

load("@foo//bar:baz.bzl", "rule1", "rule2")
load("@foo//bar:qux.bzl", rule3 = "rule1")  # Rename to avoid name collision

load("@foo//bar:symbols.bzl", "a", "b", c = "z")  # Multiple symbols

# Common pattern:  wrapping rules, passing along args
def _wrapped_rule(name, *args, **kwargs):
    rule(name="wrapped_{}".format(name), *args, added_arg="wrapped", **kwargs)

# Common pattern:  generating multiple rules in a single def
def _multiplexed_rules(name, **kwargs):
    lib_name = "{}_lib".format(name)
    rule1(name=lib_name, flag=kwargs.get('flag'))
    rule2(name="{}_bin".format(name), deps=[":" + lib_name])

    # Conditional rule
    if 'variation' in kwargs:
        variation_name = "{}_{}".format(name, kwargs['variation'])
        rule3(name = variation_name, debug=True, **kwargs)

# Common pattern:  use definition in list comprehension
_forbidden = [b]

[_wrapped_rule(name=symbol, 'arg', flag=True) for symbol in (a, b, c) if symbol not in _forbidden]

api = struct(
    multistep = _multiplexed_rules,
    wrapped = _wrapped_rule,
)
