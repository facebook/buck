""" Module docstring """

def foo():
    """ Function docstring """
    a = attr.source_list()
    if repr(a) != "<attr.source_list>":
        fail("Expected attr.source_list instance")
    a = attr.source_list(mandatory=True, doc="Some source_list", default=["BUCK"])
    if repr(a) != "<attr.source_list>":
        fail("Expected attr.source_list instance")
    a = attr.source_list(mandatory=True, doc="Some source_list", default=["BUCK"], allow_empty=True)
    if repr(a) != "<attr.source_list>":
        fail("Expected attr.source_list instance")
    a = attr.source_list(mandatory=True, doc="Some source_list", default=["BUCK"], allow_empty=False)
    if repr(a) != "<attr.source_list>":
        fail("Expected attr.source_list instance")
