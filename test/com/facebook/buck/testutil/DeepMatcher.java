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

import java.util.List;
import java.util.Objects;
import org.easymock.EasyMock;
import org.easymock.IArgumentMatcher;

public final class DeepMatcher<T> implements IArgumentMatcher {
  private final T expected;

  public DeepMatcher(T expected) {
    this.expected = expected;
  }

  public void appendTo(StringBuffer buffer) {
    buffer.append("DeepMatcher");
    buffer.append(String.valueOf(expected));
  }

  public boolean matches(Object argument) {
    if (expected instanceof List && argument instanceof List) {
      // List.equals uses Object.equals on its elements, which uses identity for arrays.
      // TODO: Nest this logic deeper than one level.
      List e = (List) expected;
      List a = (List) argument;
      int size = e.size();
      if (size != a.size()) {
        return false;
      }
      for (int i = 0; i < size; ++i) {
        if (!Objects.deepEquals(e.get(i), a.get(i))) {
          return false;
        }
      }
      return true;
    } else {
      return Objects.deepEquals(expected, argument);
    }
  }

  public static <T> T deepMatch(T expected) {
    EasyMock.reportMatcher(new DeepMatcher(expected));
    return null;
  }
}
