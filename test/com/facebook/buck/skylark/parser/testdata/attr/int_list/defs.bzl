""" Module docstring """

def well_formed():
    """ Function docstring """
    a = attr.int_list()
    if repr(a) != "<attr.int_list>":
        fail("Expected attr.int_list instance")
    a = attr.int_list(mandatory = True, doc = "Some int_list", default = [1])
    if repr(a) != "<attr.int_list>":
        fail("Expected attr.int_list instance")
    a = attr.int_list(mandatory = True, doc = "Some int_list", default = [1], allow_empty = True)
    if repr(a) != "<attr.int_list>":
        fail("Expected attr.int_list instance")
    a = attr.int_list(mandatory = True, doc = "Some int_list", default = [1], allow_empty = False)
    if repr(a) != "<attr.int_list>":
        fail("Expected attr.int_list instance")

def malformed():
    """ Function docstring """
    _a = attr.int_list(mandatory = True, doc = "Some int_list", default = 3, allow_empty = True)
