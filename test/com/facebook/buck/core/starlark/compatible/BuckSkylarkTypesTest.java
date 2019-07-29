/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.starlark.compatible;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkList;
import java.util.Objects;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuckSkylarkTypesTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  static class FakeClass<T> {
    private final T value;

    FakeClass(T value) {
      this.value = value;
    }

    T getValue() {
      return value;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof FakeClass)) {
        return false;
      }
      FakeClass<?> fakeClass = (FakeClass<?>) o;
      return Objects.equals(value, fakeClass.value);
    }

    @Override
    public int hashCode() {
      return Objects.hash(value);
    }
  }

  @Test
  public void toJavaListFailsOnWrongType() throws EvalException {
    SkylarkList<?> skylarkList = SkylarkList.Tuple.of(1, 2, 3);

    thrown.expect(EvalException.class);
    BuckSkylarkTypes.toJavaList(skylarkList, FakeClass.class, null);
  }

  @Test
  public void toJavaListCastsGenericsProperly() throws EvalException {
    SkylarkList.Tuple<?> skylarkList =
        SkylarkList.Tuple.<FakeClass<?>>of(
            new FakeClass<>("foo"), new FakeClass<>(1), new FakeClass<>(false));

    ImmutableList<FakeClass<?>> list =
        BuckSkylarkTypes.toJavaList(skylarkList, FakeClass.class, null);
    assertEquals(
        ImmutableList.of(new FakeClass<>("foo"), new FakeClass<>(1), new FakeClass<>(false)), list);
  }

  @Test
  public void toJavaListNonGenericsProperly() throws EvalException {
    SkylarkList<?> skylarkList = SkylarkList.Tuple.of(1, 2, 3);
    ImmutableList<Integer> list = BuckSkylarkTypes.toJavaList(skylarkList, Integer.class, null);

    assertEquals(ImmutableList.of(1, 2, 3), list);
  }
}
