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

import collections
from json import JSONEncoder

from .select_support import SelectorList, SelectorValue


# A JSONEncoder subclass which handles map-like and list-like objects.
class BuckJSONEncoder(JSONEncoder):
    def __init__(self):
        super(BuckJSONEncoder, self).__init__()

    def default(self, obj):
        if isinstance(obj, collections.Mapping) and isinstance(
            obj, collections.Sized
        ):  # nopep8
            return dict(obj)
        elif isinstance(obj, collections.Iterable) and isinstance(
            obj, collections.Sized
        ):
            return list(obj)
        elif isinstance(obj, SelectorValue):
            return {
                "@type": "SelectorValue",
                "conditions": obj.conditions(),
                "no_match_error": obj.no_match_message(),
            }
        elif isinstance(obj, SelectorList):
            return {"@type": "SelectorList", "items": obj.items()}
        else:
            return super(BuckJSONEncoder, self).default(obj)
