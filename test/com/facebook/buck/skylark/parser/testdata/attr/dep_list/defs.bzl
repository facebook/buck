""" Module docstring """

def well_formed():
    """ Function docstring """
    a = attr.dep_list()
    if repr(a) != "<attr.dep_list>":
        fail("Expected attr.dep_list instance")
    a = attr.dep_list(mandatory = True, doc = "Some dep_list", default = ["//dep_list/well_formed:out.txt"])
    if repr(a) != "<attr.dep_list>":
        fail("Expected attr.dep_list instance")
    a = attr.dep_list(mandatory = True, doc = "Some dep_list", default = ["//dep_list/well_formed:out.txt"], allow_empty = True)
    if repr(a) != "<attr.dep_list>":
        fail("Expected attr.dep_list instance")
    a = attr.dep_list(mandatory = True, doc = "Some dep_list", default = ["//dep_list/well_formed:out.txt"], allow_empty = False)
    if repr(a) != "<attr.dep_list>":
        fail("Expected attr.dep_list instance")

def malformed_default():
    """ Function docstring """
    _a = attr.dep_list(mandatory = True, doc = "Some dep_list", default = 3, allow_empty = True)

def malformed_providers():
    """ Function docstring """
    _a = attr.dep_list(mandatory = True, doc = "Some dep_list", default = ["//dep_list/well_formed:out.txt"], allow_empty = True, providers = [1])
