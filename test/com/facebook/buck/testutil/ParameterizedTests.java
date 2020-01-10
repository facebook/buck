/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.testutil;

import com.google.common.collect.ImmutableList;

public class ParameterizedTests {

  private ParameterizedTests() {}

  /** @return the cross-product of the input iterables, useful for parameterized tests. */
  public static ImmutableList<Object[]> getPermutations(
      ImmutableList<? extends Iterable<?>> iterables) {
    ImmutableList.Builder<Object[]> perms = ImmutableList.builder();
    if (iterables.size() == 1) {
      iterables.get(0).forEach(o -> perms.add(new Object[] {o}));
    } else if (iterables.size() > 1) {
      ImmutableList<Object[]> childPerms = getPermutations(iterables.subList(1, iterables.size()));
      for (Object object : iterables.get(0)) {
        for (Object[] childPerm : childPerms) {
          Object[] perm = new Object[iterables.size()];
          perm[0] = object;
          System.arraycopy(childPerm, 0, perm, 1, childPerm.length);
          perms.add(perm);
        }
      }
    }
    return perms.build();
  }

  public static ImmutableList<Object[]> getPermutations(Iterable<?>... iterables) {
    return getPermutations(ImmutableList.copyOf(iterables));
  }
}
