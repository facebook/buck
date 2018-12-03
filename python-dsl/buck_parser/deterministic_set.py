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


class DeterministicSet(set):
    """
    Set-like data structure with deterministic iteration order.

    In addition to set operations it also adds ability to use '+' operator
    for joining two depsets and 'to_list' for convenient conversion to list.
    """

    def __init__(self, elements=None):
        set.__init__(self, elements or [])

    def __iter__(self):
        # make the order deterministic by sorting the underlying set.
        # Technically there are more efficient ways to implement this, but
        # this one is the easiest one :)
        for element in sorted(set.__iter__(self)):
            yield element

    def to_list(self):
        """Converts this depset into a deterministically ordered list."""
        return sorted(self)

    def __add__(self, other):
        """Joins two depsets into a single one."""
        return self.union(other)
