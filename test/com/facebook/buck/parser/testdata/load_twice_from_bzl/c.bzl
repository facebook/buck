# @nolint

load(":a.bzl", "x")
load(":a.bzl", "y")

load(":b.bzl", "z")
load("//:b.bzl", "w")

s = "x=%s y=%s z=%s w=%s" % (x, y, z, w)
