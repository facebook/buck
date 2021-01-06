""" Module docstring """

def foo():
    lbl = Label("@repo//package/sub:target")
    native.genrule(
        name = "foo",
        cmd = "echo " + lbl.name + " > $OUT",
        out = "foo.out",
    )
