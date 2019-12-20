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

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.devtools.build.lib.syntax.EvalException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuckStarlarkStructObjectTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @BuckStyleValue
  public abstract static class TestStructLikeClass extends BuckStarlarkStructObject {

    public abstract String getFoo();

    @Override
    public Class<?> getDeclaredClass() {
      return TestStructLikeClass.class;
    }
  }

  @Test
  public void findsGettersForStructLikeClasses() throws NoSuchMethodException, EvalException {

    TestStructLikeClass structLikeClass = ImmutableTestStructLikeClass.of("foo");

    assertEquals(structLikeClass.getFoo(), structLikeClass.getValue("get_foo"));
  }

  @Test
  public void throwsEvalExceptionWhenNoMatchingField() throws NoSuchMethodException, EvalException {

    TestStructLikeClass structLikeClass = ImmutableTestStructLikeClass.of("foo");

    expectedException.expect(EvalException.class);
    structLikeClass.getValue("get_bar");
  }
}
