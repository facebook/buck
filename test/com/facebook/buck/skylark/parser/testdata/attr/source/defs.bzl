""" Module docstring """

def well_formed():
    """ Function docstring """
    a = attr.source()
    if repr(a) != "<attr.source>":
        fail("Expected attr.source instance")
    a = attr.source(mandatory = True, doc = "Some source", default = "BUCK")
    if repr(a) != "<attr.source>":
        fail("Expected attr.source instance")
    a = attr.source(mandatory = True, doc = "Some source", default = "BUCK")
    if repr(a) != "<attr.source>":
        fail("Expected attr.source instance")
    a = attr.source(mandatory = True, doc = "Some source", default = "BUCK")
    if repr(a) != "<attr.source>":
        fail("Expected attr.source instance")

def malformed_default():
    """ Function docstring """
    _a = attr.source(mandatory = True, doc = "Some source", default = 3)
