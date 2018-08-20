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

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class LocationMacroTypeCoercerTest {

  private static final ProjectFilesystem FILESYSTEM = new FakeProjectFilesystem();
  private static final CellPathResolver CELL_PATH_RESOLVER = TestCellPathResolver.get(FILESYSTEM);
  private static final Path BASE_PATH = Paths.get("");

  @Test
  public void validTarget() throws CoerceFailedException {
    LocationMacroTypeCoercer coercer = new LocationMacroTypeCoercer(new BuildTargetTypeCoercer());
    assertThat(
        coercer.coerce(CELL_PATH_RESOLVER, FILESYSTEM, BASE_PATH, ImmutableList.of("//:test")),
        Matchers.equalTo(
            LocationMacro.of(BuildTargetFactory.newInstance("//:test"), Optional.empty())));

    assertThat(
        coercer.coerce(CELL_PATH_RESOLVER, FILESYSTEM, BASE_PATH, ImmutableList.of("//:test[foo]")),
        Matchers.equalTo(
            LocationMacro.of(BuildTargetFactory.newInstance("//:test"), Optional.of("foo"))));
  }

  @Test(expected = CoerceFailedException.class)
  public void invalidTarget() throws CoerceFailedException {
    LocationMacroTypeCoercer coercer = new LocationMacroTypeCoercer(new BuildTargetTypeCoercer());
    coercer.coerce(CELL_PATH_RESOLVER, FILESYSTEM, BASE_PATH, ImmutableList.of("not a target"));
  }

  @Test(expected = CoerceFailedException.class)
  public void tooManyArgs() throws CoerceFailedException {
    LocationMacroTypeCoercer coercer = new LocationMacroTypeCoercer(new BuildTargetTypeCoercer());
    coercer.coerce(
        CELL_PATH_RESOLVER, FILESYSTEM, BASE_PATH, ImmutableList.of("not", "a", "target"));
  }

  @Test(expected = CoerceFailedException.class)
  public void tooFewArgs() throws CoerceFailedException {
    LocationMacroTypeCoercer coercer = new LocationMacroTypeCoercer(new BuildTargetTypeCoercer());
    coercer.coerce(CELL_PATH_RESOLVER, FILESYSTEM, BASE_PATH, ImmutableList.of());
  }
}
