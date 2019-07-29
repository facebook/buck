""" Module docstring """

def well_formed():
    """ Function docstring """
    a = attr.dep()
    if repr(a) != "<attr.dep>":
        fail("Expected attr.dep instance")
    a = attr.dep(mandatory=True, doc="Some dep", default="BUCK")
    if repr(a) != "<attr.dep>":
        fail("Expected attr.dep instance")
    a = attr.dep(mandatory=True, doc="Some dep", default="BUCK")
    if repr(a) != "<attr.dep>":
        fail("Expected attr.dep instance")
    a = attr.dep(mandatory=True, doc="Some dep", default="BUCK")
    if repr(a) != "<attr.dep>":
        fail("Expected attr.dep instance")

def malformed():
    """ Function docstring """
    _a = attr.dep(mandatory=True, doc="Some dep", default=3)
