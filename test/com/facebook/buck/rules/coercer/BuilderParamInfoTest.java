/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.coercer;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.ErrorLogger;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import org.immutables.value.Value;
import org.junit.Test;

public class BuilderParamInfoTest {
  @Test
  public void failedCoercionIncludesClassAndFieldNames() {
    try {
      CoercedTypeCache.INSTANCE
          .getAllParamInfo(new DefaultTypeCoercerFactory(), DtoWithBadField.class)
          .values();
      fail("Expected exception.");
    } catch (Exception e) {
      String message = ErrorLogger.getUserFriendlyMessage(e);
      assertThat(
          message,
          containsString(
              "no type coercer for type: class com.facebook.buck.rules.coercer.BuilderParamInfoTest$BadFieldType"));
      assertThat(message, containsString("DtoWithBadField$Builder.badField"));
    }
  }

  @Test
  public void optionalsForAbstractClass() {
    for (ParamInfo param :
        CoercedTypeCache.INSTANCE
            .getAllParamInfo(new DefaultTypeCoercerFactory(), DtoWithOptionals.class)
            .values()) {
      assertTrue("Expected param " + param.getName() + " to be optional", param.isOptional());
    }
  }

  @Test
  public void optionalsForInterface() {
    for (ParamInfo param :
        CoercedTypeCache.INSTANCE
            .getAllParamInfo(new DefaultTypeCoercerFactory(), DtoWithOptionalsFromInterface.class)
            .values()) {
      assertTrue("Expected param " + param.getName() + " to be optional", param.isOptional());
    }
  }

  @Test
  public void set() throws Exception {
    DtoWithOneParameter.Builder builder = DtoWithOneParameter.builder();
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    getParamInfo()
        .set(
            TestCellPathResolver.get(filesystem),
            filesystem,
            Paths.get("/doesnotexist"),
            EmptyTargetConfiguration.INSTANCE,
            builder,
            "foo");
    assertEquals("foo", builder.build().getSomeString());
  }

  @Test
  public void get() {
    assertEquals(
        "foo", getParamInfo().get(DtoWithOneParameter.builder().setSomeString("foo").build()));
  }

  @Test
  public void getName() {
    assertEquals("someString", getParamInfo().getName());
  }

  @Test
  public void getPythonName() {
    assertEquals("some_string", getParamInfo().getPythonName());
  }

  private ParamInfo getParamInfo() {
    return Iterables.getOnlyElement(
        CoercedTypeCache.INSTANCE
            .getAllParamInfo(new DefaultTypeCoercerFactory(), DtoWithOneParameter.class)
            .values());
  }

  class BadFieldType {}

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithBadField {
    abstract BadFieldType getBadFieldType();
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithOptionals {
    abstract Optional<String> getOptional();

    abstract Optional<ImmutableSet<String>> getOptionalImmutableSet();

    abstract Set<String> getSet();

    abstract ImmutableSet<String> getImmutableSet();

    abstract SortedSet<String> getSortedSet();

    abstract ImmutableSortedSet<String> getImmutableSortedSet();

    abstract List<String> getList();

    abstract ImmutableList<String> getImmutableList();

    abstract Map<String, String> getMap();

    abstract ImmutableMap<String, String> getImmutableMap();
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractDtoWithOptionalsFromInterface {
    Optional<String> getOptional();

    Optional<ImmutableSet<String>> getOptionalImmutableSet();

    Set<String> getSet();

    ImmutableSet<String> getImmutableSet();

    SortedSet<String> getSortedSet();

    ImmutableSortedSet<String> getImmutableSortedSet();

    List<String> getList();

    ImmutableList<String> getImmutableList();

    Map<String, String> getMap();

    ImmutableMap<String, String> getImmutableMap();
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithOneParameter {
    abstract String getSomeString();
  }
}
