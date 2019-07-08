""" Module docstring """

def foo():
    """ Function docstring """
    a = attr.bool()
    if repr(a) != "<attr.bool>":
        fail("Expected attr.bool instance")
    a = attr.bool(mandatory=True, doc="Some bool", default=True)
    if repr(a) != "<attr.bool>":
        fail("Expected attr.bool instance")
