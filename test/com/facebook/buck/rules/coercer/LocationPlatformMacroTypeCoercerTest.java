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

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.macros.LocationPlatformMacro;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class LocationPlatformMacroTypeCoercerTest {

  private final ProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final CellNameResolver cellNameResolver =
      TestCellPathResolver.get(filesystem).getCellNameResolver();
  private final ForwardRelativePath basePath = ForwardRelativePath.of("");

  private LocationPlatformMacroTypeCoercer coercer;

  @Before
  public void setUp() {
    coercer =
        new LocationPlatformMacroTypeCoercer(
            new BuildTargetWithOutputsTypeCoercer(
                new UnconfiguredBuildTargetWithOutputsTypeCoercer(
                    new UnconfiguredBuildTargetTypeCoercer(
                        new ParsingUnconfiguredBuildTargetViewFactory()))));
  }

  @Test
  public void target() throws CoerceFailedException {
    assertThat(
        coercer.coerce(
            cellNameResolver,
            filesystem,
            basePath,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            ImmutableList.of("//:test")),
        Matchers.equalTo(
            LocationPlatformMacro.of(
                BuildTargetWithOutputs.of(
                    BuildTargetFactory.newInstance("//:test"), OutputLabel.defaultLabel()),
                ImmutableSet.of())));
    assertThat(
        coercer.coerce(
            cellNameResolver,
            filesystem,
            basePath,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            ImmutableList.of("//:test[foo]")),
        Matchers.equalTo(
            LocationPlatformMacro.of(
                BuildTargetWithOutputs.of(
                    BuildTargetFactory.newInstance("//:test"), OutputLabel.of("foo")),
                ImmutableSet.of())));
  }

  @Test
  public void targetWithFlavors() throws CoerceFailedException {
    assertThat(
        coercer.coerce(
            cellNameResolver,
            filesystem,
            basePath,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            ImmutableList.of("//:test", "flavor1", "flavor2")),
        Matchers.equalTo(
            LocationPlatformMacro.of(
                BuildTargetWithOutputs.of(
                    BuildTargetFactory.newInstance("//:test"), OutputLabel.defaultLabel()),
                ImmutableSet.of(InternalFlavor.of("flavor1"), InternalFlavor.of("flavor2")))));
  }

  @Test(expected = CoerceFailedException.class)
  public void invalidTarget() throws CoerceFailedException {
    coercer.coerce(
        cellNameResolver,
        filesystem,
        basePath,
        UnconfiguredTargetConfiguration.INSTANCE,
        UnconfiguredTargetConfiguration.INSTANCE,
        ImmutableList.of("not a target"));
  }

  @Test(expected = CoerceFailedException.class)
  public void tooFewArgs() throws CoerceFailedException {
    coercer.coerce(
        cellNameResolver,
        filesystem,
        basePath,
        UnconfiguredTargetConfiguration.INSTANCE,
        UnconfiguredTargetConfiguration.INSTANCE,
        ImmutableList.of());
  }
}
