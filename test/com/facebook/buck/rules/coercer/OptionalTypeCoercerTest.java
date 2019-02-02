/*
 * Copyright 2016-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class OptionalTypeCoercerTest {

  private static final ProjectFilesystem FILESYSTEM = new FakeProjectFilesystem();
  private static final Path PATH_RELATIVE_TO_PROJECT_ROOT = Paths.get("");

  @Rule public ExpectedException exception = ExpectedException.none();

  @Test
  public void nullIsAbsent() throws CoerceFailedException {
    OptionalTypeCoercer<Void> coercer =
        new OptionalTypeCoercer<>(new IdentityTypeCoercer<>(Void.class));
    Optional<Void> result =
        coercer.coerce(
            TestCellBuilder.createCellRoots(FILESYSTEM),
            FILESYSTEM,
            PATH_RELATIVE_TO_PROJECT_ROOT,
            EmptyTargetConfiguration.INSTANCE,
            null);
    assertThat(result, Matchers.equalTo(Optional.empty()));
  }

  @Test
  public void nonNullIsPresent() throws CoerceFailedException {
    OptionalTypeCoercer<String> coercer = new OptionalTypeCoercer<>(new StringTypeCoercer());
    Optional<String> result =
        coercer.coerce(
            TestCellBuilder.createCellRoots(FILESYSTEM),
            FILESYSTEM,
            PATH_RELATIVE_TO_PROJECT_ROOT,
            EmptyTargetConfiguration.INSTANCE,
            "something");
    assertThat(result, Matchers.equalTo(Optional.of("something")));
  }

  @Test
  public void nestedOptionals() {
    exception.expect(IllegalArgumentException.class);
    exception.expectMessage("Nested optional fields are ambiguous.");
    new OptionalTypeCoercer<>(new OptionalTypeCoercer<>(new IdentityTypeCoercer<>(Void.class)));
  }

  @Test
  public void testConcatOfAbsentElementsIsAbsent() {
    OptionalTypeCoercer<String> coercer =
        new OptionalTypeCoercer<>(new IdentityTypeCoercer<>(String.class));

    assertFalse(coercer.concat(Arrays.asList(Optional.empty(), Optional.empty())).isPresent());
  }

  @Test
  public void testConcatOfPresentNonConcatableElementsIsAbsent() {
    PairTypeCoercer<String, String> pairTypeCoercer =
        new PairTypeCoercer<>(
            new IdentityTypeCoercer<>(String.class), new IdentityTypeCoercer<>(String.class));
    OptionalTypeCoercer<Pair<String, String>> coercer = new OptionalTypeCoercer<>(pairTypeCoercer);

    assertNull(
        coercer.concat(
            Arrays.asList(Optional.of(new Pair<>("a", "b")), Optional.of(new Pair<>("b", "c")))));
  }

  @Test
  public void testConcatOfPresentConcatableElementsReturnsAggregatedResult() {
    ListTypeCoercer<String> listTypeCoercer =
        new ListTypeCoercer<>(new IdentityTypeCoercer<>(String.class));
    OptionalTypeCoercer<ImmutableList<String>> coercer = new OptionalTypeCoercer<>(listTypeCoercer);

    assertEquals(
        ImmutableList.of("b", "a", "a", "c"),
        coercer
            .concat(
                Arrays.asList(
                    Optional.of(ImmutableList.of("b", "a")),
                    Optional.of(ImmutableList.of("a", "c"))))
            .get());
  }
}
