# ==================================================================================================
# Copyright 2013 Twitter, Inc.
# --------------------------------------------------------------------------------------------------
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this work except in compliance with the License.
# You may obtain a copy of the License in the LICENSE file, or at:
#
#  http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
# ==================================================================================================
# OrderedSet recipe referenced in the Python standard library docs (bottom):
#     http://docs.python.org/library/collections.html
#
# Copied from recipe code found here: http://code.activestate.com/recipes/576694/ with small
# modifications
#

import collections


class OrderedSet(collections.MutableSet):
  KEY, PREV, NEXT = range(3)

  def __init__(self, iterable=None):
    self.end = end = []
    end += [None, end, end]         # sentinel node for doubly linked list
    self.map = {}                   # key --> [key, prev, next]
    if iterable is not None:
      self |= iterable

  def __len__(self):
    return len(self.map)

  def __contains__(self, key):
    return key in self.map

  def add(self, key):
    if key not in self.map:
      end = self.end
      curr = end[self.PREV]
      curr[self.NEXT] = end[self.PREV] = self.map[key] = [key, curr, end]

  def update(self, iterable):
    for key in iterable:
      self.add(key)

  def discard(self, key):
    if key in self.map:
      key, prev, next = self.map.pop(key)
      prev[self.NEXT] = next
      next[self.PREV] = prev

  def __iter__(self):
    end = self.end
    curr = end[self.NEXT]
    while curr is not end:
      yield curr[self.KEY]
      curr = curr[self.NEXT]

  def __reversed__(self):
    end = self.end
    curr = end[self.PREV]
    while curr is not end:
      yield curr[self.KEY]
      curr = curr[self.PREV]

  def pop(self, last=True):
    if not self:
      raise KeyError('set is empty')
    key = next(reversed(self)) if last else next(iter(self))
    self.discard(key)
    return key

  def __repr__(self):
    if not self:
      return '%s()' % (self.__class__.__name__,)
    return '%s(%r)' % (self.__class__.__name__, list(self))

  def __eq__(self, other):
    if isinstance(other, OrderedSet):
      return len(self) == len(other) and list(self) == list(other)
    return set(self) == set(other)

  def __del__(self):
    self.clear()                    # remove circular references
