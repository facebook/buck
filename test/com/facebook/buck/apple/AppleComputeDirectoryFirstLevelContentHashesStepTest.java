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

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Test;

public class AppleComputeDirectoryFirstLevelContentHashesStepTest {

  private Path filesystemRootPath;
  private ProjectFilesystem filesystem;
  private StepExecutionContext context;

  @Before
  public void setUp() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    filesystemRootPath =
        TestDataHelper.getTestDataDirectory(this)
            .resolve("compute_directory_1st_level_content_hashes");
    filesystem = TestProjectFilesystems.createProjectFilesystem(filesystemRootPath);
    context = TestExecutionContext.newInstance();
  }

  @Test
  public void test_canary() throws IOException, InterruptedException {
    // Not possible to check in empty dir, so recreate it manually
    filesystem.mkdirs(Paths.get("empty_dir"));
    ImmutableMap.Builder<RelPath, String> pathToHashBuilder = ImmutableMap.builder();
    Step step =
        new AppleComputeDirectoryFirstLevelContentHashesStep(
            AbsPath.of(filesystemRootPath), filesystem, pathToHashBuilder);
    step.execute(context);
    ImmutableMap<RelPath, String> pathToHash = pathToHashBuilder.build();
    assertEquals(pathToHash.size(), 5);
    assertEquals(
        "da39a3ee5e6b4b0d3255bfef95601890afd80709", pathToHash.get(RelPath.get("empty_dir")));
    assertEquals(
        "11d6a78d31734cef3615ce272ad2aacaf1f65a1c", pathToHash.get(RelPath.get("dir_with_file")));
    assertEquals(
        "sha1:4e1243bd22c66e76c2ba9eddc1f91394e57f9f83", pathToHash.get(RelPath.get("foo")));
    assertEquals(
        "0beec7b5ea3f0fdbc95d0dd47f3c5bc275da8a33", pathToHash.get(RelPath.get("symlink")));
    assertEquals(
        "7818bc1016ecc2fa6ab4042cd77cd5c0ca80cab1",
        pathToHash.get(RelPath.get("nested_dirs_with_file")));
  }
}
