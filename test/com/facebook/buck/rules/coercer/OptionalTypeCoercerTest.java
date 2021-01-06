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

package com.facebook.buck.rules.coercer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.cell.nameresolver.TestCellNameResolver;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.util.types.Unit;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class OptionalTypeCoercerTest {

  private static final ProjectFilesystem FILESYSTEM = new FakeProjectFilesystem();
  private static final ForwardRelativePath PATH_RELATIVE_TO_PROJECT_ROOT =
      ForwardRelativePath.of("");

  @Rule public ExpectedException exception = ExpectedException.none();

  @Test
  public void nullIsAbsent() throws CoerceFailedException {
    OptionalTypeCoercer<Unit, Unit> coercer =
        new OptionalTypeCoercer<>(new IdentityTypeCoercer<>(Unit.class));
    Optional<Unit> result =
        coercer.coerceBoth(
            TestCellBuilder.createCellRoots(FILESYSTEM).getCellNameResolver(),
            FILESYSTEM,
            PATH_RELATIVE_TO_PROJECT_ROOT,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            null);
    assertThat(result, Matchers.equalTo(Optional.empty()));
  }

  @Test
  public void emptyIsEmpty() throws CoerceFailedException {
    OptionalTypeCoercer<Unit, Unit> coercer =
        new OptionalTypeCoercer<>(new IdentityTypeCoercer<>(Unit.class));
    Optional<Unit> result =
        coercer.coerceBoth(
            TestCellBuilder.createCellRoots(FILESYSTEM).getCellNameResolver(),
            FILESYSTEM,
            PATH_RELATIVE_TO_PROJECT_ROOT,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            Optional.empty());
    assertThat(result, Matchers.equalTo(Optional.empty()));
  }

  @Test
  public void nonNullIsPresent() throws CoerceFailedException {
    OptionalTypeCoercer<String, String> coercer =
        new OptionalTypeCoercer<>(new StringTypeCoercer());
    Optional<String> result =
        coercer.coerceBoth(
            TestCellBuilder.createCellRoots(FILESYSTEM).getCellNameResolver(),
            FILESYSTEM,
            PATH_RELATIVE_TO_PROJECT_ROOT,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
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
    OptionalTypeCoercer<String, String> coercer =
        new OptionalTypeCoercer<>(new IdentityTypeCoercer<>(String.class));

    assertFalse(coercer.concat(Arrays.asList(Optional.empty(), Optional.empty())).isPresent());
  }

  @Test
  public void testConcatOfPresentNonConcatableElementsIsAbsent() {
    PairTypeCoercer<String, String, String, String> pairTypeCoercer =
        new PairTypeCoercer<>(
            new IdentityTypeCoercer<>(String.class), new IdentityTypeCoercer<>(String.class));
    OptionalTypeCoercer<?, Pair<String, String>> coercer =
        new OptionalTypeCoercer<>(pairTypeCoercer);

    assertNull(
        coercer.concat(
            Arrays.asList(Optional.of(new Pair<>("a", "b")), Optional.of(new Pair<>("b", "c")))));
  }

  @Test
  public void testConcatOfPresentConcatableElementsReturnsAggregatedResult() {
    ListTypeCoercer<String, String> listTypeCoercer =
        new ListTypeCoercer<>(new IdentityTypeCoercer<>(String.class));
    OptionalTypeCoercer<?, ImmutableList<String>> coercer =
        new OptionalTypeCoercer<>(listTypeCoercer);

    assertEquals(
        ImmutableList.of("b", "a", "a", "c"),
        coercer
            .concat(
                Arrays.asList(
                    Optional.of(ImmutableList.of("b", "a")),
                    Optional.of(ImmutableList.of("a", "c"))))
            .get());
  }

  @Test
  public void coerceUnconfiguredToConfiguredOptimizedIdentity() throws Exception {
    OptionalTypeCoercer<String, String> coercer =
        new OptionalTypeCoercer<>(new StringTypeCoercer());

    Optional<String> input = Optional.of("aaa");
    Optional<String> coerced =
        coercer.coerce(
            TestCellNameResolver.forRoot(),
            new FakeProjectFilesystem(),
            ForwardRelativePath.EMPTY,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            input);
    assertSame(input, coerced);
  }
}
