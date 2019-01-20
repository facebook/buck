# Skylib

Skylib is a standard library that provides functions useful for manipulating
collections, file paths, and other features that are useful when writing custom
build rules in Bazel.

> This library is currently under early development. Be aware that the APIs
> in these modules may change during this time.

Each of the `.bzl` files in the `lib` directory defines a "module"&mdash;a
`struct` that contains a set of related functions and/or other symbols that can
be loaded as a single unit, for convenience. The top-level file `lib.bzl` acts
as an index from which the other modules can be imported.

To use the functionality here, import the modules you need from `lib.bzl` and
access the symbols by dotting into those structs:

```python
load("@bazel_skylib//:lib.bzl", "paths", "shell")

p = paths.basename("foo.bar")
s = shell.quote(p)
```

## List of modules

* [collections](lib/collections.bzl)
* [dicts](lib/dicts.bzl)
* [paths](lib/paths.bzl)
* [selects](lib/selects.bzl)
* [sets](lib/sets.bzl)
* [shell](lib/shell.bzl)
* [unittest](lib/unittest.bzl)

## Writing a new module

Steps to add a module to Skylib:

1. Create a new `.bzl` file in the `lib` directory.

1. Write the functions or other symbols (such as constants) in that file,
   defining them privately (prefixed by an underscore).

1. Create the exported module struct, mapping the public names of the symbols
   to their implementations. For example, if your module was named `things` and
   had a function named `manipulate`, your `things.bzl` file would look like
   this:

   ```python
   def _manipulate():
     ...

   things = struct(
       manipulate=_manipulate,
   )
   ```

1. Add a line to `lib.bzl` to make the new module accessible from the index:

   ```python
   load("@bazel_skylib//lib:things.bzl", "things")
   ```

1. Clients can then use the module by loading it from `lib.bzl`:

   ```python
   load("@bazel_skylib//:lib.bzl", "things")

   things.manipulate()
   ```

1. Add unit tests for your module in the `tests` directory.

## `skylark_library`

The `skylark_library.bzl` rule can be used to aggregate a set of
Skylark files and its dependencies for use in test targets and
documentation generation.
