/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.testutil;

import com.google.common.collect.ImmutableList;

import java.util.Arrays;

public class ParameterizedTests {

  private ParameterizedTests() {}

  /**
   * @return the cross-product of the input iterables, useful for parameterized tests.
   */
  public static ImmutableList<Object[]> getPermutations(Iterable<?>... iterables) {
    if (iterables.length == 0) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<Object[]> perms = ImmutableList.builder();
    ImmutableList<Object[]> childPerms =
        getPermutations(Arrays.copyOfRange(iterables, 1, iterables.length));
    for (Object object : iterables[0]) {
      if (!childPerms.isEmpty()) {
        for (Object[] childPerm : childPerms) {
          Object[] perm = new Object[iterables.length];
          perm[0] = object;
          System.arraycopy(childPerm, 0, perm, 1, childPerm.length);
          perms.add(perm);
        }
      } else {
        perms.add(new Object[]{object});
      }
    }
    return perms.build();
  }

}
