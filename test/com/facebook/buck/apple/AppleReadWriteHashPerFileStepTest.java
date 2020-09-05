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

package com.facebook.buck.apple;

import static junit.framework.TestCase.assertEquals;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AppleReadWriteHashPerFileStepTest {

  private ProjectFilesystem filesystem;
  private StepExecutionContext context;

  @Rule public final TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    Path filesystemRootPath = tmp.newFolder().getPath();
    filesystem = TestProjectFilesystems.createProjectFilesystem(filesystemRootPath);
    context = TestExecutionContext.newInstance();
  }

  @Test
  public void testSameHashesAreReadThatBeenWritten() throws IOException, InterruptedException {
    ImmutableMap<RelPath, String> pathToHash =
        ImmutableMap.of(RelPath.get("foo"), "bar", RelPath.get("a/b/c"), "baz");
    {
      Supplier<ImmutableMap<RelPath, String>> pathToHashSupplier = () -> pathToHash;
      Step step =
          new AppleWriteHashPerFileStep(
              "write", pathToHashSupplier, Paths.get("path_to_hash.json"), filesystem);
      step.execute(context);
    }
    ImmutableMap.Builder<RelPath, String> pathToHashBuilder = ImmutableMap.builder();
    {
      Step step =
          new AppleReadHashPerFileStep(
              "read", filesystem.getPathForRelativePath("path_to_hash.json"), pathToHashBuilder);
      step.execute(context);
    }
    assertEquals(pathToHash, pathToHashBuilder.build());
  }
}
