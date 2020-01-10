""" Simple tests to make sure that Labels work properly """

def _assert_equal(expected, actual):
    """ Assert things are equal """
    if expected != actual:
        fail("Expected {}, got {}".format(expected, actual))

def test_from_root_cell():
    """ Tests to run from /BUCK """
    lbl = Label("@foo//bar/baz:fizz")
    _assert_equal("foo", lbl.workspace_name)
    _assert_equal("bar/baz", lbl.package)
    _assert_equal("fizz", lbl.name)

    lbl = Label("//bar/baz:fizz")
    _assert_equal("", lbl.workspace_name)
    _assert_equal("bar/baz", lbl.package)
    _assert_equal("fizz", lbl.name)

    _assert_equal(Label("//foo/bar:baz").relative(":quux"), Label("//foo/bar:quux"))
    _assert_equal(Label("//foo/bar:baz").relative("//wiz:quux"), Label("//wiz:quux"))
    _assert_equal(Label("@repo//foo/bar:baz").relative("//wiz:quux"), Label("@repo//wiz:quux"))
    _assert_equal(Label("@repo//foo/bar:baz").relative("//visibility:public"), Label("//visibility:public"))
    _assert_equal(Label("@repo//foo/bar:baz").relative("@other//wiz:quux"), Label("@other//wiz:quux"))

def test_loaded_from_root_including_cell():
    """ Tests to run from subdir/BUCK """
    lbl = Label("@foo//bar/baz:fizz")
    _assert_equal("foo", lbl.workspace_name)
    _assert_equal("bar/baz", lbl.package)
    _assert_equal("fizz", lbl.name)

    lbl = Label("//bar/baz:fizz")
    _assert_equal("root_cell", lbl.workspace_name)
    _assert_equal("bar/baz", lbl.package)
    _assert_equal("fizz", lbl.name)

    _assert_equal(Label("//foo/bar:baz").relative(":quux"), Label("//foo/bar:quux"))
    _assert_equal(Label("//foo/bar:baz").relative("//wiz:quux"), Label("//wiz:quux"))
    _assert_equal(Label("@repo//foo/bar:baz").relative("//wiz:quux"), Label("@repo//wiz:quux"))
    _assert_equal(Label("@repo//foo/bar:baz").relative("//visibility:public"), Label("//visibility:public"))
    _assert_equal(Label("@repo//foo/bar:baz").relative("@other//wiz:quux"), Label("@other//wiz:quux"))

def test_from_child_cell():
    """ Tests to run from cell/BUCK (in cell @b) """
    lbl = Label("@foo//bar/baz:fizz")
    _assert_equal("foo", lbl.workspace_name)
    _assert_equal("bar/baz", lbl.package)
    _assert_equal("fizz", lbl.name)

    # When instantiating a Label object directly in a .bzl file, the cell is always
    # relative to the cell that the .bzl file lives in, not the caller's cell.
    # (When we convert implicitly from strings for attributes, we make it relative
    #  to the caller's cell)
    # Since we call this from cell 'b', but .bzl lives in 'a', make sure that 'a' is
    # used.
    lbl = Label("//bar/baz:fizz")
    _assert_equal("root_cell", lbl.workspace_name)
    _assert_equal("bar/baz", lbl.package)
    _assert_equal("fizz", lbl.name)

    _assert_equal(Label("//foo/bar:baz").relative(":quux"), Label("//foo/bar:quux"))
    _assert_equal(Label("//foo/bar:baz").relative("//wiz:quux"), Label("//wiz:quux"))
    _assert_equal(Label("@repo//foo/bar:baz").relative("//wiz:quux"), Label("@repo//wiz:quux"))
    _assert_equal(Label("@repo//foo/bar:baz").relative("//visibility:public"), Label("//visibility:public"))
    _assert_equal(Label("@repo//foo/bar:baz").relative("@other//wiz:quux"), Label("@other//wiz:quux"))
