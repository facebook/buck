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

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.UnconfiguredBuildTargetFactoryForTests;
import com.facebook.buck.core.model.UnconfiguredBuildTargetWithOutputs;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.BiFunction;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class TargetWithOutputsTypeCoercerTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  private static final ForwardRelativePath BASE_PATH =
      ForwardRelativePath.of("java/com/facebook/buck/example");
  private static final ProjectFilesystem FILESYSTEM = new FakeProjectFilesystem();
  private static final BiFunction<String, OutputLabel, UnconfiguredBuildTargetWithOutputs>
      EXPECTED_UNCONFIGURED_BUILD_TARGET_WITH_OUTPUTS_BI_FUNCTION =
          (bt, ol) ->
              UnconfiguredBuildTargetWithOutputs.of(
                  UnconfiguredBuildTargetFactoryForTests.newInstance(FILESYSTEM, bt), ol);
  private static final BiFunction<String, OutputLabel, BuildTargetWithOutputs>
      EXPECTED_BUILD_TARGET_WITH_OUTPUTS_BI_FUNCTION =
          (bt, ol) -> BuildTargetWithOutputs.of(BuildTargetFactory.newInstance(bt), ol);

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object> data() {
    return Arrays.asList(
        new Object[][] {
          {
            new UnconfiguredBuildTargetWithOutputsTypeCoercer(
                new UnconfiguredBuildTargetTypeCoercer(
                    new ParsingUnconfiguredBuildTargetViewFactory())),
            EXPECTED_UNCONFIGURED_BUILD_TARGET_WITH_OUTPUTS_BI_FUNCTION
          },
          {
            new BuildTargetWithOutputsTypeCoercer(
                new UnconfiguredBuildTargetWithOutputsTypeCoercer(
                    new UnconfiguredBuildTargetTypeCoercer(
                        new ParsingUnconfiguredBuildTargetViewFactory()))),
            EXPECTED_BUILD_TARGET_WITH_OUTPUTS_BI_FUNCTION
          }
        });
  }

  @Parameterized.Parameter() public TypeCoercer testCoercer;

  @Parameterized.Parameter(value = 1)
  public BiFunction<String, OutputLabel, ?> expected;

  @Test
  public void canCoerceBuildTargetWithoutAlias() throws CoerceFailedException {
    Object seen =
        testCoercer.coerceBoth(
            createCellRoots(FILESYSTEM).getCellNameResolver(),
            FILESYSTEM,
            BASE_PATH,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "//foo:bar");

    assertEquals(expected.apply("//foo:bar", OutputLabel.defaultLabel()), seen);
  }

  @Test
  public void canCoerceBuildTargetCoercerWithAlias() throws CoerceFailedException {
    Object seen =
        testCoercer.coerceBoth(
            createCellRoots(FILESYSTEM).getCellNameResolver(),
            FILESYSTEM,
            BASE_PATH,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "//foo:bar[whee]");

    assertEquals(expected.apply("//foo:bar", OutputLabel.of("whee")), seen);
  }

  @Test
  public void throwsIfOutputLabelIsEmpty() throws CoerceFailedException {
    thrown.expect(CoerceFailedException.class);
    thrown.expectMessage(containsString("Output label cannot be empty"));

    testCoercer.coerceBoth(
        createCellRoots(FILESYSTEM).getCellNameResolver(),
        FILESYSTEM,
        BASE_PATH,
        UnconfiguredTargetConfiguration.INSTANCE,
        UnconfiguredTargetConfiguration.INSTANCE,
        "//foo:bar[]");
  }

  @Test
  public void invalidAliasSyntaxThrowException() throws CoerceFailedException {
    thrown.expect(CoerceFailedException.class);
    thrown.expectMessage(containsString("Could not parse output label for //foo:bar[whee"));

    testCoercer.coerceBoth(
        createCellRoots(FILESYSTEM).getCellNameResolver(),
        FILESYSTEM,
        BASE_PATH,
        UnconfiguredTargetConfiguration.INSTANCE,
        UnconfiguredTargetConfiguration.INSTANCE,
        "//foo:bar[whee");
  }

  @Test
  public void canCoerceFlavoredTarget() throws CoerceFailedException {
    Object seen =
        testCoercer.coerceBoth(
            createCellRoots(FILESYSTEM).getCellNameResolver(),
            FILESYSTEM,
            BASE_PATH,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "//foo:bar#src[whee]");

    assertEquals(expected.apply("//foo:bar#src", OutputLabel.of("whee")), seen);
  }

  @Test
  public void canCoerceMultipleFlavors() throws CoerceFailedException {
    Object seen =
        testCoercer.coerceBoth(
            createCellRoots(FILESYSTEM).getCellNameResolver(),
            FILESYSTEM,
            BASE_PATH,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "//foo:bar#flavor1,flavor2[whee]");

    assertEquals(expected.apply("//foo:bar#flavor1,flavor2", OutputLabel.of("whee")), seen);
  }

  @Test
  public void targetFlavorCannotComeBeforeAlias() throws CoerceFailedException {
    thrown.expect(CoerceFailedException.class);
    thrown.expectMessage(containsString("Output label must come last in //foo:bar[whee]#src"));

    testCoercer.coerceBoth(
        createCellRoots(FILESYSTEM).getCellNameResolver(),
        FILESYSTEM,
        BASE_PATH,
        UnconfiguredTargetConfiguration.INSTANCE,
        UnconfiguredTargetConfiguration.INSTANCE,
        "//foo:bar[whee]#src");
  }

  @Test
  public void canCoerceFlavorsWithoutAlias() throws CoerceFailedException {
    Object seen =
        testCoercer.coerceBoth(
            createCellRoots(FILESYSTEM).getCellNameResolver(),
            FILESYSTEM,
            BASE_PATH,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "//foo:bar#flavor1,flavor2");

    assertEquals(expected.apply("//foo:bar#flavor1,flavor2", OutputLabel.defaultLabel()), seen);
  }

  @Test
  public void canCoerceShortTarget() throws CoerceFailedException {
    Object seen =
        testCoercer.coerceBoth(
            createCellRoots(FILESYSTEM).getCellNameResolver(),
            FILESYSTEM,
            BASE_PATH,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            ":hangry");

    assertEquals(
        expected.apply("//java/com/facebook/buck/example:hangry", OutputLabel.defaultLabel()),
        seen);
  }

  @Test
  public void canCoerceShortTargetWithAlias() throws CoerceFailedException {
    Object seen =
        testCoercer.coerceBoth(
            createCellRoots(FILESYSTEM).getCellNameResolver(),
            FILESYSTEM,
            BASE_PATH,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            ":bar[whee]");

    assertEquals(
        expected.apply("//java/com/facebook/buck/example:bar", OutputLabel.of("whee")), seen);
  }

  @Test
  public void canCoerceShortTargetWithFlavorAndAlias() throws CoerceFailedException {
    Object seen =
        testCoercer.coerceBoth(
            createCellRoots(FILESYSTEM).getCellNameResolver(),
            FILESYSTEM,
            BASE_PATH,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            ":bar#yum[whee]");

    assertEquals(
        expected.apply("//java/com/facebook/buck/example:bar#yum", OutputLabel.of("whee")), seen);
  }
}
