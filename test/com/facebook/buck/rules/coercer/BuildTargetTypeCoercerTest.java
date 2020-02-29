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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuildTargetTypeCoercerTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  private ProjectFilesystem filesystem = new FakeProjectFilesystem();
  private ForwardRelativePath basePath = ForwardRelativePath.of("java/com/facebook/buck/example");

  private UnconfiguredBuildTargetTypeCoercer unconfiguredBuildTargetTypeCoercer;

  @Before
  public void setUp() {
    unconfiguredBuildTargetTypeCoercer =
        new UnconfiguredBuildTargetTypeCoercer(new ParsingUnconfiguredBuildTargetViewFactory());
  }

  @Test
  public void canCoerceAnUnflavoredFullyQualifiedTarget() throws CoerceFailedException {
    BuildTarget seen =
        new BuildTargetTypeCoercer(unconfiguredBuildTargetTypeCoercer)
            .coerceBoth(
                createCellRoots(filesystem).getCellNameResolver(),
                filesystem,
                basePath,
                UnconfiguredTargetConfiguration.INSTANCE,
                UnconfiguredTargetConfiguration.INSTANCE,
                "//foo:bar");

    assertEquals(BuildTargetFactory.newInstance("//foo:bar"), seen);
  }

  @Test
  public void failedCoerce() throws CoerceFailedException {
    thrown.expect(CoerceFailedException.class);
    thrown.expectMessage(containsString("Unable to find the target //foo::bar."));
    new BuildTargetTypeCoercer(unconfiguredBuildTargetTypeCoercer)
        .coerceBoth(
            createCellRoots(filesystem).getCellNameResolver(),
            filesystem,
            basePath,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "//foo::bar");
  }

  @Test
  public void shouldCoerceAShortTarget() throws CoerceFailedException {
    BuildTarget seen =
        new BuildTargetTypeCoercer(unconfiguredBuildTargetTypeCoercer)
            .coerceBoth(
                createCellRoots(filesystem).getCellNameResolver(),
                filesystem,
                basePath,
                UnconfiguredTargetConfiguration.INSTANCE,
                UnconfiguredTargetConfiguration.INSTANCE,
                ":bar");

    assertEquals(BuildTargetFactory.newInstance("//java/com/facebook/buck/example:bar"), seen);
  }

  @Test
  public void shouldCoerceATargetWithASingleFlavor() throws CoerceFailedException {
    BuildTarget seen =
        new BuildTargetTypeCoercer(unconfiguredBuildTargetTypeCoercer)
            .coerceBoth(
                createCellRoots(filesystem).getCellNameResolver(),
                filesystem,
                basePath,
                UnconfiguredTargetConfiguration.INSTANCE,
                UnconfiguredTargetConfiguration.INSTANCE,
                "//foo:bar#baz");

    assertEquals(BuildTargetFactory.newInstance("//foo:bar#baz"), seen);
  }

  @Test
  public void shouldCoerceMultipleFlavors() throws CoerceFailedException {
    BuildTarget seen =
        new BuildTargetTypeCoercer(unconfiguredBuildTargetTypeCoercer)
            .coerceBoth(
                createCellRoots(filesystem).getCellNameResolver(),
                filesystem,
                basePath,
                UnconfiguredTargetConfiguration.INSTANCE,
                UnconfiguredTargetConfiguration.INSTANCE,
                "//foo:bar#baz,qux");

    assertEquals(BuildTargetFactory.newInstance("//foo:bar#baz,qux"), seen);
  }

  @Test
  public void shouldCoerceAShortTargetWithASingleFlavor() throws CoerceFailedException {
    BuildTarget seen =
        new BuildTargetTypeCoercer(unconfiguredBuildTargetTypeCoercer)
            .coerceBoth(
                createCellRoots(filesystem).getCellNameResolver(),
                filesystem,
                basePath,
                UnconfiguredTargetConfiguration.INSTANCE,
                UnconfiguredTargetConfiguration.INSTANCE,
                ":bar#baz");

    BuildTarget expected =
        BuildTargetFactory.newInstance("//java/com/facebook/buck/example:bar#baz");
    assertEquals(expected, seen);
  }
}
