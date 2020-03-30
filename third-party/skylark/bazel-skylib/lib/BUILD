load("//:skylark_library.bzl", "skylark_library")

licenses(["notice"])

package(default_visibility = ["//:__pkg__"])

filegroup(
    name = "test_deps",
    srcs = ["BUILD"] + glob(["*.bzl"]),
    testonly = True,
    visibility = ["//visibility:public"],
)

skylark_library(
    name = "collections",
    srcs = ["collections.bzl"],
)

skylark_library(
    name = "dicts",
    srcs = ["dicts.bzl"],
)

skylark_library(
    name = "paths",
    srcs = ["paths.bzl"],
)

skylark_library(
    name = "selects",
    srcs = ["selects.bzl"],
)

skylark_library(
    name = "sets",
    srcs = ["sets.bzl"],
)

skylark_library(
    name = "shell",
    srcs = ["shell.bzl"],
)

skylark_library(
    name = "structs",
    srcs = ["structs.bzl"],
)

skylark_library(
    name = "unittest",
    srcs = ["unittest.bzl"],
    deps = [":sets"],
)
