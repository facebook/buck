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

import static com.facebook.buck.rules.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.ObjectMappers;

import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class ParamInfoTest {

  private Path testPath = Paths.get("path");
  private TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory(
      ObjectMappers.newDefaultInstance());

  public static class WithUpperBound<X extends SourcePath> {
    public X path;
  }

  @Test
  public void shouldReportWildcardsWithUpperBoundsAsUpperBound() throws NoSuchFieldException {
    Field field = WithUpperBound.class.getField("path");
    ParamInfo info = new ParamInfo(typeCoercerFactory, WithUpperBound.class, field);

    Class<?> type = info.getResultClass();
    assertEquals(SourcePath.class, type);
  }

  public static class OptionalWithWildcard {
    public Optional<? extends SourcePath> path;
  }

  @Test
  public void anOptionalFieldMayBeWildcardedWithAnUpperBound() throws NoSuchFieldException {
    Field field = OptionalWithWildcard.class.getField("path");
    ParamInfo info = new ParamInfo(typeCoercerFactory, OptionalWithWildcard.class, field);

    Class<?> type = info.getResultClass();
    assertEquals(Optional.class, type);
  }

  public static class UnboundedWildcard {
    public Optional<?> bad;
  }

  @Test(expected = IllegalArgumentException.class)
  public void wildcardedFieldsWithNoUpperBoundAreNotAllowed() throws NoSuchFieldException {
    Field field = UnboundedWildcard.class.getField("bad");
    new ParamInfo(typeCoercerFactory, UnboundedWildcard.class, field);
  }

  public static class WithLowerBound {
    public Optional<? super SourcePath> bad;
  }

  @Test(expected = IllegalArgumentException.class)
  public void superTypesForGenericsAreNotAllowedEither() throws NoSuchFieldException {
    Field field = WithLowerBound.class.getField("bad");
    new ParamInfo(typeCoercerFactory, WithLowerBound.class, field);
  }

  public static class PythonNames {
    public String isDefaultName;

    @Hint(name = "not_the_default_name_123")
    public String notDefaultName;
  }

  @Test
  public void pythonNamesShouldBeCorrectlyGenerated() throws NoSuchFieldException {
    ParamInfo info;

    info = new ParamInfo(
        typeCoercerFactory,
        PythonNames.class,
        PythonNames.class.getField("isDefaultName"));
    assertEquals("is_default_name", info.getPythonName());

    info = new ParamInfo(
        typeCoercerFactory,
        PythonNames.class,
        PythonNames.class.getField("notDefaultName"));
    assertEquals("not_the_default_name_123", info.getPythonName());
  }

  public static class OptionalString {
    public Optional<String> field;
  }

  @Test
  public void fieldSetForOptionalFields() throws Exception {
    OptionalString example = new OptionalString();

    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    ParamInfo info = new ParamInfo(
        typeCoercerFactory,
        OptionalString.class,
        OptionalString.class.getField("field"));

    info.set(createCellRoots(filesystem), filesystem, testPath, example, null);
    assertEquals(Optional.empty(), example.field);

    info.set(createCellRoots(filesystem), filesystem, testPath, example, "");
    assertEquals(Optional.of(""), example.field);

    info.set(createCellRoots(filesystem), filesystem, testPath, example, "foo");
    assertEquals(Optional.of("foo"), example.field);
  }

  public static class DefaultString {
    public String field = "FIELD";
  }

  @Test
  public void fieldSetForDefaultFields() throws Exception {
    DefaultString example = new DefaultString();

    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    ParamInfo info = new ParamInfo(
        typeCoercerFactory,
        DefaultString.class,
        DefaultString.class.getField("field"));

    info.set(createCellRoots(filesystem), filesystem, testPath, example, null);
    assertEquals("FIELD", example.field);

    info.set(createCellRoots(filesystem), filesystem, testPath, example, "");
    assertEquals("", example.field);

    info.set(createCellRoots(filesystem), filesystem, testPath, example, "foo");
    assertEquals("foo", example.field);
  }
}
