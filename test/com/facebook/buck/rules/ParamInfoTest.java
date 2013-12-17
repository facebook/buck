/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;

import com.google.common.base.Optional;

import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ParamInfoTest {

  private Path path = Paths.get("path");

  @Test
  public void shouldReportWildcardsWithUpperBoundsAsUpperBound() throws NoSuchFieldException {
    class Example<X extends SourcePath> {
      @SuppressWarnings("unused")
      public X path;
    }

    Field field = Example.class.getField("path");
    ParamInfo info = new ParamInfo(path, field);

    Class<?> type = info.getType();
    assertEquals(SourcePath.class, type);
  }

  @Test
  public void anOptionalFieldMayBeWildcardedWithAnUpperBound() throws NoSuchFieldException {
    class Example {
      @SuppressWarnings("unused")
      public Optional<? extends SourcePath> path;
    }

    Field field = Example.class.getField("path");
    ParamInfo info = new ParamInfo(path, field);

    Class<?> type = info.getType();
    assertEquals(SourcePath.class, type);
  }

  @Test(expected = IllegalArgumentException.class)
  public void wildcardedFieldsWithNoUpperBoundAreNotAllowed() throws NoSuchFieldException {
    class Example {
      @SuppressWarnings("unused")
      public Optional<?> bad;
    }

    Field field = Example.class.getField("bad");
    new ParamInfo(path, field);
  }

  @Test(expected = IllegalArgumentException.class)
  public void superTypesForGenericsAreNotAllowedEither() throws NoSuchFieldException {
    class Example {
      @SuppressWarnings("unused")
      public Optional<? super SourcePath> bad;
    }

    Field field = Example.class.getField("bad");
    new ParamInfo(path, field);
  }
}
