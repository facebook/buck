""" Module docstring """

def well_formed():
    """ Function docstring """
    a = attr.int()
    if repr(a) != "<attr.int>":
        fail("Expected attr.int instance")
    a = attr.int(mandatory = True, doc = "Some int", default = 3)
    if repr(a) != "<attr.int>":
        fail("Expected attr.int instance")
    a = attr.int(mandatory = True, doc = "Some int", default = 3, values = [1, 2, 3])
    if repr(a) != "<attr.int>":
        fail("Expected attr.int instance")
    a = attr.int(mandatory = True, doc = "Some int", default = 3, values = [])
    if repr(a) != "<attr.int>":
        fail("Expected attr.int instance")

def malformed():
    """ Function docstring """
    _a = attr.int(mandatory = True, doc = "Some int", default = 3, values = ["foo"])
