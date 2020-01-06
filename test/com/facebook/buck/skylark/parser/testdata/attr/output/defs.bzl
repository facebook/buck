""" Module docstring """

def well_formed():
    """ Function docstring """
    a = attr.output()
    if repr(a) != "<attr.output>":
        fail("Expected attr.output instance")
    a = attr.output(default = None)
    if repr(a) != "<attr.output>":
        fail("Expected attr.output instance")
    a = attr.output(mandatory = False, doc = "Some output", default = "out.txt")
    if repr(a) != "<attr.output>":
        fail("Expected attr.output instance")
    a = attr.output(mandatory = True, doc = "Some output", default = "out.txt")
    if repr(a) != "<attr.output>":
        fail("Expected attr.output instance")
    a = attr.output(mandatory = True, doc = "Some output", default = "out.txt")
    if repr(a) != "<attr.output>":
        fail("Expected attr.output instance")

def malformed():
    """ Function docstring """
    _a = attr.output(mandatory = True, doc = "Some output", default = 3)

def no_default():
    _a = attr.output(mandatory = False, doc = "Some output")
