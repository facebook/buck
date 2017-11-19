"""Contains build rules for Buck modules"""

load("//tools/build_rules:java_rules.bzl", "java_library_with_plugins")

def buck_module(
    name,
    **kwargs
):
    java_library_with_plugins(
        name = name,
        **kwargs
    )

    java_binary(
        name = name + "-module",
        visibility = [
            "//programs:bucklib",
            "//programs:calculate-buck-binary-hash",
        ],
        deps = [
            ":" + name,
        ],
    )

def get_module_binary(module):
  """ Returns target for module's binary """
  return "{}-module".format(module)

def convert_modules_to_resources(buck_modules):
  """ Converts modules to a map with resources for packaging in a Python binary """
  result = {}

  for k, v in buck_modules.items():
    result["buck-modules/{}.jar".format(k)] = get_module_binary(v)

  return result
