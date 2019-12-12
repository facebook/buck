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

import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import java.nio.file.InvalidPathException;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class PathTypeCoercerTest {

  private ProjectFilesystem filesystem;
  private final ForwardRelativePath pathRelativeToProjectRoot = ForwardRelativePath.of("");
  private final PathTypeCoercer pathTypeCoercer = new PathTypeCoercer();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Before
  public void setUp() {
    filesystem = new FakeProjectFilesystem().setIgnoreValidityOfPaths(false);
  }

  @Test
  public void coercingInvalidPathThrowsException() throws CoerceFailedException {
    expectedException.expect(CoerceFailedException.class);
    expectedException.expectMessage("invalid path");

    String invalidPath = "";
    pathTypeCoercer.coerce(
        createCellRoots(filesystem),
        filesystem,
        pathRelativeToProjectRoot,
        UnconfiguredTargetConfiguration.INSTANCE,
        UnconfiguredTargetConfiguration.INSTANCE,
        invalidPath);
  }

  @Test
  public void coercingOtherInvalidPathThrowsException() throws CoerceFailedException {
    String invalidPath = "foo/bar\0";

    expectedException.expect(CoerceFailedException.class);
    expectedException.expectMessage(String.format("Could not convert '%s' to a Path", invalidPath));
    expectedException.expectCause(Matchers.instanceOf(InvalidPathException.class));

    pathTypeCoercer.coerce(
        createCellRoots(filesystem),
        filesystem,
        pathRelativeToProjectRoot,
        UnconfiguredTargetConfiguration.INSTANCE,
        UnconfiguredTargetConfiguration.INSTANCE,
        invalidPath);
  }

  @Test
  public void coercingMissingFileDoesNotThrow() throws Exception {
    String missingPath = "hello";
    new PathTypeCoercer()
        .coerce(
            createCellRoots(filesystem),
            filesystem,
            pathRelativeToProjectRoot,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            missingPath);
  }
}
