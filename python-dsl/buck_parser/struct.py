# Copyright 2018-present Facebook, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

from __future__ import absolute_import, division, print_function, with_statement

import json
from collections import OrderedDict
from json.encoder import (
    INFINITY,
    JSONEncoder,
    _make_iterencode,
    encode_basestring,
    encode_basestring_ascii,
)
from keyword import iskeyword as _iskeyword
from operator import itemgetter as _itemgetter
from typing import Callable, Iterable, Type


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

        def floatstr(
            o,
            allow_nan=self.allow_nan,
            _repr=float.__repr__,
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
    struct_class = create_struct_class(kwargs.keys())
    return struct_class(**kwargs)


_CLASS_CACHE = {}


def _create_struct_repr(field_names):
    # type: (Iterable[str]) -> Callable
    """Creates a repr function that should be used for struct instances."""
    repr_fmt = "(" + ", ".join(name + "=%r" for name in field_names) + ")"

    def __repr__(self):
        """Return a nicely formatted representation string"""
        return self.__class__.__name__ + repr_fmt % self

    return __repr__


def _asdict(self):
    """Return a new OrderedDict which maps field names to their values."""
    return OrderedDict(zip(self._fields, self))


def _to_json(self):
    """Creates a JSON string representation of this struct instance."""
    return json.dumps(self, cls=StructEncoder, separators=(",", ":"), sort_keys=True)


def create_struct_class(field_names):
    # type: (Iterable[str]) -> Type

    field_names = tuple(field_names)
    struct_class = _CLASS_CACHE.get(field_names)
    if struct_class:
        return struct_class

    # Variables used in the methods and docstrings

    for name in field_names:
        if not all(c.isalnum() or c == "_" for c in name):
            raise ValueError(
                "Field names can only contain alphanumeric characters and underscores: %r"
                % name
            )
        if _iskeyword(name):
            raise ValueError("Field names cannot be a keyword: %r" % name)
        if name[0].isdigit():
            raise ValueError("Field names cannot start with a number: %r" % name)

    arg_list = repr(field_names).replace("'", "")[1:-1]
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
    exec(s, namespace)
    __new__ = namespace["__new__"]

    # Build-up the class namespace dictionary
    # and use type() to build the result class
    class_namespace = {
        "__slots__": (),
        "_fields": field_names,
        "__new__": __new__,
        "__repr__": _create_struct_repr(field_names),
        "_asdict": _asdict,
        "to_json": _to_json,
    }
    cache = _nt_itemgetters
    for index, name in enumerate(field_names):
        try:
            itemgetter_object = cache[index]
        except KeyError:
            itemgetter_object = _itemgetter(index)
            cache[index] = itemgetter_object
        class_namespace[name] = property(itemgetter_object)

    struct_class = type(_TYPENAME, (tuple,), class_namespace)
    _CLASS_CACHE[field_names] = struct_class
    return struct_class
