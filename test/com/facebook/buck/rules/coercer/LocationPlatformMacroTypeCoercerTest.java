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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.macros.LocationPlatformMacro;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class LocationPlatformMacroTypeCoercerTest {

  private static final ProjectFilesystem FILESYSTEM = new FakeProjectFilesystem();
  private static final CellPathResolver CELL_PATH_RESOLVER = TestCellPathResolver.get(FILESYSTEM);
  private static final ForwardRelativePath BASE_PATH = ForwardRelativePath.of("");

  private UnconfiguredBuildTargetTypeCoercer unconfiguredBuildTargetFactory;

  @Before
  public void setUp() {
    unconfiguredBuildTargetFactory =
        new UnconfiguredBuildTargetTypeCoercer(new ParsingUnconfiguredBuildTargetViewFactory());
  }

  @Test
  public void target() throws CoerceFailedException {
    LocationPlatformMacroTypeCoercer coercer =
        new LocationPlatformMacroTypeCoercer(
            new BuildTargetTypeCoercer(unconfiguredBuildTargetFactory));
    assertThat(
        coercer.coerce(
            CELL_PATH_RESOLVER,
            FILESYSTEM,
            BASE_PATH,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            ImmutableList.of("//:test")),
        Matchers.equalTo(
            LocationPlatformMacro.of(
                BuildTargetFactory.newInstance("//:test"), Optional.empty(), ImmutableSet.of())));
    assertThat(
        coercer.coerce(
            CELL_PATH_RESOLVER,
            FILESYSTEM,
            BASE_PATH,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            ImmutableList.of("//:test[foo]")),
        Matchers.equalTo(
            LocationPlatformMacro.of(
                BuildTargetFactory.newInstance("//:test"), Optional.of("foo"), ImmutableSet.of())));
  }

  @Test
  public void targetWithFlavors() throws CoerceFailedException {
    LocationPlatformMacroTypeCoercer coercer =
        new LocationPlatformMacroTypeCoercer(
            new BuildTargetTypeCoercer(unconfiguredBuildTargetFactory));
    assertThat(
        coercer.coerce(
            CELL_PATH_RESOLVER,
            FILESYSTEM,
            BASE_PATH,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            ImmutableList.of("//:test", "flavor1", "flavor2")),
        Matchers.equalTo(
            LocationPlatformMacro.of(
                BuildTargetFactory.newInstance("//:test"),
                Optional.empty(),
                ImmutableSet.of(InternalFlavor.of("flavor1"), InternalFlavor.of("flavor2")))));
  }

  @Test(expected = CoerceFailedException.class)
  public void invalidTarget() throws CoerceFailedException {
    LocationPlatformMacroTypeCoercer coercer =
        new LocationPlatformMacroTypeCoercer(
            new BuildTargetTypeCoercer(unconfiguredBuildTargetFactory));
    coercer.coerce(
        CELL_PATH_RESOLVER,
        FILESYSTEM,
        BASE_PATH,
        UnconfiguredTargetConfiguration.INSTANCE,
        UnconfiguredTargetConfiguration.INSTANCE,
        ImmutableList.of("not a target"));
  }

  @Test(expected = CoerceFailedException.class)
  public void tooFewArgs() throws CoerceFailedException {
    LocationPlatformMacroTypeCoercer coercer =
        new LocationPlatformMacroTypeCoercer(
            new BuildTargetTypeCoercer(unconfiguredBuildTargetFactory));
    coercer.coerce(
        CELL_PATH_RESOLVER,
        FILESYSTEM,
        BASE_PATH,
        UnconfiguredTargetConfiguration.INSTANCE,
        UnconfiguredTargetConfiguration.INSTANCE,
        ImmutableList.of());
  }
}
