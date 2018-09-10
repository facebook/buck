from __future__ import absolute_import, division, print_function, with_statement

import json
from collections import OrderedDict
from json.encoder import (
    FLOAT_REPR,
    INFINITY,
    JSONEncoder,
    _make_iterencode,
    encode_basestring,
    encode_basestring_ascii,
)
from operator import itemgetter as _itemgetter


class StructEncoder(JSONEncoder):
    """Extends built-in JSONEncoder to support Struct serialization."""

    def iterencode(self, o, _one_shot=False):
        """Custom implementation of iterencode that is almost identical to the
        built-in JSONEncoder.iterencode implementation but changes the way
        structs are serialized.

        By default JSONEncoder would serialize a namedtuple-like instance into
        a list, but it should be serialized into a dictionary. In order to do
        this, the isinstance function passed to _make_iterencode is changed to
        always return False for namedtuple-like instances, so that they can be
        handled using a self.default method.

        This implementation is not using c_make_encoder because it does not
        provide a way to customize struct serialization.
        """
        if self.check_circular:
            markers = {}
        else:
            markers = None
        if self.ensure_ascii:
            _encoder = encode_basestring_ascii
        else:
            _encoder = encode_basestring
        if self.encoding != "utf-8":

            def _encoder(o, _orig_encoder=_encoder, _encoding=self.encoding):
                if isinstance(o, str):
                    o = o.decode(_encoding)
                return _orig_encoder(o)

        def floatstr(
            o,
            allow_nan=self.allow_nan,
            _repr=FLOAT_REPR,
            _inf=INFINITY,
            _neginf=-INFINITY,
        ):
            # Check for specials.  Note that this type of test is processor
            # and/or platform-specific, so do tests which don't depend on the
            # internals.

            if o != o:
                text = "NaN"
            elif o == _inf:
                text = "Infinity"
            elif o == _neginf:
                text = "-Infinity"
            else:
                return _repr(o)

            if not allow_nan:
                raise ValueError(
                    "Out of range float values are not JSON compliant: " + repr(o)
                )

            return text

        _iterencode = _make_iterencode(
            markers,
            self.default,
            _encoder,
            self.indent,
            floatstr,
            self.key_separator,
            self.item_separator,
            self.sort_keys,
            self.skipkeys,
            _one_shot,
            isinstance=self.isinstance,
        )
        return _iterencode(o, 0)

    @staticmethod
    def isinstance(obj, cls):
        if isinstance(obj, tuple) and hasattr(obj, "_asdict"):
            return False
        return isinstance(obj, cls)

    def default(self, obj):
        if isinstance(obj, tuple) and hasattr(obj, "_asdict"):
            return obj._asdict()


_nt_itemgetters = {}


_TYPENAME = "struct"


def struct(**kwargs):
    """Creates an immutable container using the keyword arguments as attributes.

    It can be used to group multiple values and/or functions together. Example:
        def _my_function():
          return 3
        s = struct(x = 2, foo = _my_function)
        return s.x + s.foo()  # returns 5

    The implementation is almost identical to namedtuple, but:
     - it does not forbid fields starting with underscores
     - does not implement methods for pickling
     - does not support copy/deepcopy
    """
    field_names = tuple(kwargs.keys())

    # Variables used in the methods and docstrings
    arg_list = repr(field_names).replace("'", "")[1:-1]
    repr_fmt = "(" + ", ".join(name + "=%r" for name in field_names) + ")"
    tuple_new = tuple.__new__

    # Create all the named tuple methods to be added to the class namespace

    s = (
        "def __new__(_cls, "
        + arg_list
        + "): return _tuple_new(_cls, ("
        + arg_list
        + "))"
    )
    namespace = {"_tuple_new": tuple_new, "__name__": _TYPENAME}
    # Note: exec() has the side-effect of interning the field names
    exec s in namespace
    __new__ = namespace["__new__"]

    def __repr__(self):
        """Return a nicely formatted representation string"""
        return self.__class__.__name__ + repr_fmt % self

    def _asdict(self):
        """Return a new OrderedDict which maps field names to their values."""
        return OrderedDict(zip(self._fields, self))

    def to_json(self):
        """Creates a JSON string representation of this struct instance."""
        return json.dumps(
            self, cls=StructEncoder, separators=(",", ":"), sort_keys=True
        )

    # Build-up the class namespace dictionary
    # and use type() to build the result class
    class_namespace = {
        "__slots__": (),
        "_fields": field_names,
        "__new__": __new__,
        "__repr__": __repr__,
        "_asdict": _asdict,
        "to_json": to_json,
    }
    cache = _nt_itemgetters
    for index, name in enumerate(field_names):
        try:
            itemgetter_object = cache[index]
        except KeyError:
            itemgetter_object = _itemgetter(index)
            cache[index] = itemgetter_object
        class_namespace[name] = property(itemgetter_object)

    result = type(_TYPENAME, (tuple,), class_namespace)

    return result(**kwargs)
