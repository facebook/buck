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
