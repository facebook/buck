""" Module docstring """

def well_formed():
    """ Function docstring """
    a = attr.bool()
    if repr(a) != "<attr.bool>":
        fail("Expected attr.bool instance")
    a = attr.bool(mandatory = True, doc = "Some bool", default = True)
    if repr(a) != "<attr.bool>":
        fail("Expected attr.bool instance")

def malformed():
    """ Function docstring """
    _a = attr.bool(mandatory = True, doc = "Some bool", default = "val")
