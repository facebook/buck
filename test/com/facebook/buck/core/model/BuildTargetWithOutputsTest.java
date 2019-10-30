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
package com.facebook.buck.core.model;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.function.BiFunction;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class BuildTargetWithOutputsTest {
  private static final ProjectFilesystem FILESYSTEM = new FakeProjectFilesystem();
  private static final BiFunction<
          String, Optional<String>, ImmutableUnconfiguredBuildTargetWithOutputs>
      UNCONFIGURED_BUILD_TARGET_WITH_OUTPUTS_GENERATOR =
          (bt, ol) ->
              ImmutableUnconfiguredBuildTargetWithOutputs.of(
                  UnconfiguredBuildTargetFactoryForTests.newInstance(FILESYSTEM, bt), ol);
  private static final BiFunction<String, Optional<String>, ImmutableBuildTargetWithOutputs>
      BUILD_TARGET_WITH_OUTPUTS_GENERATOR =
          (bt, ol) -> ImmutableBuildTargetWithOutputs.of(BuildTargetFactory.newInstance(bt), ol);

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object> data() {
    return Arrays.asList(
        new Object[] {
          UNCONFIGURED_BUILD_TARGET_WITH_OUTPUTS_GENERATOR, BUILD_TARGET_WITH_OUTPUTS_GENERATOR
        });
  }

  @Parameterized.Parameter()
  public BiFunction<String, Optional<String>, Comparable> targetGenerator;

  @Test
  public void sameTargetsWithoutOutputLabelsAreEqual() {
    assertEquals(
        targetGenerator.apply("//:sometarget", Optional.empty()),
        targetGenerator.apply("//:sometarget", Optional.empty()));
  }

  @Test
  public void sameTargetsWithSameOutputLabelsAreEqual() {
    assertEquals(
        targetGenerator.apply("//:sometarget", Optional.of("label")),
        targetGenerator.apply("//:sometarget", Optional.of("label")));
  }

  @Test
  public void differentTargetsAreWithoutOutputLabelAreNotEqual() {
    assertNotEquals(
        targetGenerator.apply("//:sometarget", Optional.empty()),
        targetGenerator.apply("//:other", Optional.empty()));
  }

  @Test
  public void targetWithOutputLabelIsGreaterThanNoOutputLabel() {
    assertThat(
        targetGenerator.apply("//:sometarget", Optional.of("label")),
        Matchers.greaterThan(targetGenerator.apply("//:sometarget", Optional.empty())));
  }

  @Test
  public void orderingIsBasedOnStringForSameTargetsWithDifferentOutputLabels() {
    assertEquals(
        "label".compareTo("other"),
        targetGenerator
            .apply("//:sometarget", Optional.of("label"))
            .compareTo(targetGenerator.apply("//:sometarget", Optional.of("other"))));
  }

  @Test
  public void toStringPrintsBracketsIfNonEmptyOutputLabel() {
    assertEquals(
        "//:sometarget[label]",
        targetGenerator.apply("//:sometarget", Optional.of("label")).toString());
  }

  @Test
  public void toStringOmitsBracketsIfEmptyOutputLabel() {
    assertEquals(
        "//:sometarget", targetGenerator.apply("//:sometarget", Optional.empty()).toString());
  }
}
