/*
 * Copyright 2015-present Facebook, Inc.
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

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Test;

public class FrameworkPathTypeCoercerTest {

  private final TypeCoercer<BuildTarget> buildTargetTypeCoercer = new BuildTargetTypeCoercer();
  private final TypeCoercer<Path> pathTypeCoercer = new PathTypeCoercer();
  private final TypeCoercer<SourcePath> sourcePathTypeCoercer =
      new SourcePathTypeCoercer(buildTargetTypeCoercer, pathTypeCoercer);
  private final TypeCoercer<FrameworkPath> frameworkPathTypeCoercer =
      new FrameworkPathTypeCoercer(sourcePathTypeCoercer);

  private FakeProjectFilesystem projectFilesystem;
  private final Path pathRelativeToProjectRoot = Paths.get("");

  @Before
  public void setUp() {
    projectFilesystem = new FakeProjectFilesystem();
  }

  @Test(expected = HumanReadableException.class)
  public void shouldRejectUnknownBuildSettingsInFrameworkEntries() throws CoerceFailedException {
    frameworkPathTypeCoercer.coerce(
        createCellRoots(projectFilesystem),
        projectFilesystem,
        pathRelativeToProjectRoot,
        "$FOOBAR/libfoo.a");
  }
}
