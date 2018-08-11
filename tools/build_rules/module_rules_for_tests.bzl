"""Contains build rules for supporting Buck modules in tests"""

def convert_module_deps_to_test(deps):
    """ Converts the given module dependencies to module dependencies for testing """
    converted_deps = []
    for dep in deps:
        converted_deps.append(dep + "_module_for_test")
    return converted_deps
