load("//:skylark_library.bzl", "skylark_library")

licenses(["notice"])  # Apache 2.0

package(default_visibility = ["//visibility:public"])

exports_files([
    "LICENSE",
    "lib.bzl",
])

filegroup(
    name = "test_deps",
    srcs = [
        "BUILD",
        "//lib:test_deps",
    ] + glob(["*.bzl"]),
    testonly = True,
)

skylark_library(
    name = "lib",
    srcs = ["lib.bzl"],
    deps = [
        "//lib:collections",
        "//lib:dicts",
        "//lib:paths",
        "//lib:selects",
        "//lib:sets",
        "//lib:shell",
        "//lib:structs",
        "//lib:unittest",
    ],
)

skylark_library(
    name = "skylark_library",
    srcs = ["skylark_library.bzl"],
)
