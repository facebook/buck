"""Lib macros."""

# the cell format intentionally uses a deprecated cell format
# @lint-ignore SKYLINT
load(
    "cell//:lib2.bzl",
    _foo = "foo",
)

foo = _foo
