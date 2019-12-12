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

package com.facebook.buck.core.starlark.compatible;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableMap;
import org.junit.Test;

public class MethodLookupTest {

  public abstract static class TestClass {
    public abstract String getStr();

    protected abstract int getInt();

    public String withParam(String s) {
      return s + s;
    }
  }

  @Test
  public void findsGettersForStructLikeClasses() throws NoSuchMethodException {

    assertEquals(
        ImmutableMap.of("get_str", TestClass.class.getMethod("getStr")),
        MethodLookup.getMethods(TestClass.class));
  }
}
