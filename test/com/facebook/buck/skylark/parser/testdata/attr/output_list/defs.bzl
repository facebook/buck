""" Module docstring """

def well_formed():
    """ Function docstring """
    a = attr.output_list()
    if repr(a) != "<attr.output_list>":
        fail("Expected attr.output_list instance")
    a = attr.output_list(mandatory = True, doc = "Some output_list", default = ["out.txt"])
    if repr(a) != "<attr.output_list>":
        fail("Expected attr.output_list instance")
    a = attr.output_list(mandatory = True, doc = "Some output_list", default = ["out.txt"], allow_empty = True)
    if repr(a) != "<attr.output_list>":
        fail("Expected attr.output_list instance")
    a = attr.output_list(mandatory = True, doc = "Some output_list", default = ["out.txt"], allow_empty = False)
    if repr(a) != "<attr.output_list>":
        fail("Expected attr.output_list instance")

def malformed():
    """ Function docstring """
    _a = attr.output_list(mandatory = True, doc = "Some output", default = 3, allow_empty = True)
