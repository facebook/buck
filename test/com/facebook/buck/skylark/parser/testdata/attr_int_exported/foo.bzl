""" Module docstring """

def foo():
    """ Function docstring """
    a = attr.int()
    if repr(a) != "<attr.int>":
        fail("Expected attr.int instance")
    a = attr.int(mandatory=True, doc="Some int", default=3)
    if repr(a) != "<attr.int>":
        fail("Expected attr.int instance")
    a = attr.int(mandatory=True, doc="Some int", default=3, values=[1,2,3])
    if repr(a) != "<attr.int>":
        fail("Expected attr.int instance")
    a = attr.int(mandatory=True, doc="Some int", default=3, values=[])
    if repr(a) != "<attr.int>":
        fail("Expected attr.int instance")
