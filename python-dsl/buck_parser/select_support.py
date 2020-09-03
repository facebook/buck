# Copyright (c) Facebook, Inc. and its affiliates.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from __future__ import absolute_import, division, print_function, with_statement

from typing import Any, Callable, List


class SelectorValue:
    """
    Represents a single select statement that contains conditions and an optional error message
    """

    def __init__(self, conditions, no_match_message):
        self.__conditions = conditions
        self.__no_match_message = no_match_message

    def conditions(self):
        return self.__conditions

    def no_match_message(self):
        return self.__no_match_message

    def __str__(self):
        return "select(" + str(self.__conditions) + ")"

    def __eq__(self, o):
        if not isinstance(o, SelectorValue):
            return False
        return self.conditions() == o.conditions()

    def __ne__(self, o):
        return not self.__eq__(o)


class SelectorList:
    """
    Represents a list of concatenated object that can be SelectorValue or objects of other types

    This is used to store the representation of select statements and resolve the actual attributes
    when configuration is present.
    """

    def __init__(self, items):
        self.__items = items

    def items(self):
        return self.__items

    def __radd__(self, obj):
        if isinstance(obj, SelectorList):
            return SelectorList(obj.items() + self.__items)
        else:
            return SelectorList([obj] + self.__items)

    def __add__(self, obj):
        if isinstance(obj, SelectorList):
            return SelectorList(self.__items + obj.items())
        else:
            return SelectorList(self.__items + [obj])

    def __str__(self):
        return " + ".join(str(i) for i in self.__items)


def select_equal(this, that):
    # type: (SelectorList, SelectorList) -> bool
    if not (isinstance(this, SelectorList) and isinstance(that, SelectorList)):
        return False

    return this.items() == that.items()


def select_map(selector_list, map_fn):
    # type: (SelectorList, Callable[[Any], Any]) -> SelectorList
    """Iterate and modify values in the select expression.

    Returns a select with the values modified.
    """
    new_items = []
    for item in selector_list.items():
        if isinstance(item, SelectorValue):
            new_conditions = {}
            for key, value in item.conditions().items():
                new_conditions[key] = map_fn(value)
            new_items.append(SelectorValue(new_conditions, item.no_match_message()))
        else:
            new_items.append(map_fn(item))

    return SelectorList(new_items)


def select_test(selector_list, test_fn):
    # type: (SelectorList, Callable[[Any], bool]) -> bool
    """Test values in the select expression using the given function.

    Returns:
        True, if any value in the select passes, else False
    """
    for item in selector_list.items():
        if isinstance(item, SelectorValue):
            for value in item.conditions().values():
                if test_fn(value):
                    return True
        else:
            if test_fn(item):
                return True

    return False
