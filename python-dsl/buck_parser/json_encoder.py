from __future__ import absolute_import, division, print_function, with_statement

import collections
from json import JSONEncoder


# A JSONEncoder subclass which handles map-like and list-like objects.
class BuckJSONEncoder(JSONEncoder):
    def __init__(self):
        super(BuckJSONEncoder, self).__init__(self, sort_keys=True)

    def default(self, obj):
        if isinstance(obj, collections.Mapping) and isinstance(
            obj, collections.Sized
        ):  # nopep8
            return dict(obj)
        elif isinstance(obj, collections.Iterable) and isinstance(
            obj, collections.Sized
        ):
            return list(obj)
        else:
            return super(BuckJSONEncoder, self).default(obj)
