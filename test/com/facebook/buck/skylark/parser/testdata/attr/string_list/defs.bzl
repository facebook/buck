""" Module docstring """

def well_formed():
    """ Function docstring """
    a = attr.string_list()
    if repr(a) != "<attr.string_list>":
        fail("Expected attr.string_list instance")
    a = attr.string_list(mandatory = True, doc = "Some string_list", default = ["foo"])
    if repr(a) != "<attr.string_list>":
        fail("Expected attr.string_list instance")
    a = attr.string_list(mandatory = True, doc = "Some string_list", default = ["foo"], allow_empty = True)
    if repr(a) != "<attr.string_list>":
        fail("Expected attr.string_list instance")
    a = attr.string_list(mandatory = True, doc = "Some string_list", default = ["foo"], allow_empty = False)
    if repr(a) != "<attr.string_list>":
        fail("Expected attr.string_list instance")

def malformed():
    """ Function docstring """
    _a = attr.string_list(mandatory = True, doc = "Some string_list", default = 3, allow_empty = True)
