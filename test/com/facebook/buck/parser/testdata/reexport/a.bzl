load(":b.bzl", _x = "x")

# Reassign to the same variable which is presented in imported module
x = _x
