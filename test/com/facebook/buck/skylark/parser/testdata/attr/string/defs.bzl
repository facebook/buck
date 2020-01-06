""" Module docstring """

def well_formed():
    """ Function docstring """
    a = attr.string()
    if repr(a) != "<attr.string>":
        fail("Expected attr.string instance")
    a = attr.string(mandatory = True, doc = "Some string", default = "default_value")
    if repr(a) != "<attr.string>":
        fail("Expected attr.string instance")
    a = attr.string(mandatory = True, doc = "Some string", default = "default_value", values = ["foo", "bar", "baz"])
    if repr(a) != "<attr.string>":
        fail("Expected attr.string instance")
    a = attr.string(mandatory = True, doc = "Some string", default = "default_value", values = [])
    if repr(a) != "<attr.string>":
        fail("Expected attr.string instance")

def malformed():
    """ Function docstring """
    _a = attr.string(mandatory = True, doc = "Some string", default = "val", values = [1, 2, 3])
