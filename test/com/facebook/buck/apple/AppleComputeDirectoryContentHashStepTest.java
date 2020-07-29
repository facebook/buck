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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Test;

public class AppleComputeDirectoryContentHashStepTest {

  private Path filesystemRootPath;
  private ProjectFilesystem filesystem;
  private StepExecutionContext context;

  @Before
  public void setUp() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    filesystemRootPath =
        TestDataHelper.getTestDataDirectory(this).resolve("compute_directory_content_hash");
    filesystem = TestProjectFilesystems.createProjectFilesystem(filesystemRootPath);
    context = TestExecutionContext.newInstance();
  }

  @Test
  public void test_givenDirectoryIsEmpty_whenContentIsHashed_thenContentHashIsSameAsOfEmptyData()
      throws IOException, InterruptedException {
    StringBuilder hashBuilder = new StringBuilder();
    Path testDirPath = Paths.get("empty_dir");
    // Not possible to check in empty dir, so recreate it manually
    filesystem.mkdirs(testDirPath);
    Step step =
        new AppleComputeDirectoryContentHashStep(
            hashBuilder, AbsPath.of(filesystemRootPath.resolve(testDirPath)), filesystem);
    step.execute(context);
    assertEquals(emptyDataHash(), hashBuilder.toString());
  }

  @Test
  public void
      test_givenDirectoryHasSingleFile_whenContentIsHashed_thenContentHashDependsBothOnFileNameAndContent()
          throws IOException, InterruptedException {
    {
      StringBuilder hashBuilder = new StringBuilder();
      Path testDirPath = Paths.get("both_file_name_and_content_matters/reference");
      Step step =
          new AppleComputeDirectoryContentHashStep(
              hashBuilder, AbsPath.of(filesystemRootPath.resolve(testDirPath)), filesystem);
      step.execute(context);
      assertEquals("f86128ae9dd03e7359efcf298ebdbfe29b997f60", hashBuilder.toString());
    }
    {
      StringBuilder hashBuilder = new StringBuilder();
      Path testDirPath = Paths.get("both_file_name_and_content_matters/different_file_name");
      Step step =
          new AppleComputeDirectoryContentHashStep(
              hashBuilder, AbsPath.of(filesystemRootPath.resolve(testDirPath)), filesystem);
      step.execute(context);
      assertEquals("531d263f4c44c59e77df3d10ff76d39f25c7b76a", hashBuilder.toString());
    }
    {
      StringBuilder hashBuilder = new StringBuilder();
      Path testDirPath = Paths.get("both_file_name_and_content_matters/different_file_content");
      Step step =
          new AppleComputeDirectoryContentHashStep(
              hashBuilder, AbsPath.of(filesystemRootPath.resolve(testDirPath)), filesystem);
      step.execute(context);
      assertEquals("2cd35fad877b017fd667aba88c0e5236ca60ebfb", hashBuilder.toString());
    }
  }

  @Test
  public void
      test_givenDirectoryHasSymlink_whenContentIsHashed_thenPointerValueOnlyIsHashedWithoutPointeeContent()
          throws IOException, InterruptedException {
    {
      StringBuilder hashBuilder = new StringBuilder();
      Path testDirPath = Paths.get("with_symlink/reference/dir");
      Step step =
          new AppleComputeDirectoryContentHashStep(
              hashBuilder, AbsPath.of(filesystemRootPath.resolve(testDirPath)), filesystem);
      step.execute(context);
      assertEquals("12ff1d234b1707ff535dd4b3fb1da6278bdc8854", hashBuilder.toString());
    }
    {
      StringBuilder hashBuilder = new StringBuilder();
      Path testDirPath = Paths.get("with_symlink/pointee_changed/dir");
      Step step =
          new AppleComputeDirectoryContentHashStep(
              hashBuilder, AbsPath.of(filesystemRootPath.resolve(testDirPath)), filesystem);
      step.execute(context);
      assertEquals("12ff1d234b1707ff535dd4b3fb1da6278bdc8854", hashBuilder.toString());
    }
    {
      StringBuilder hashBuilder = new StringBuilder();
      Path testDirPath = Paths.get("with_symlink/pointer_changed/dir");
      Step step =
          new AppleComputeDirectoryContentHashStep(
              hashBuilder, AbsPath.of(filesystemRootPath.resolve(testDirPath)), filesystem);
      step.execute(context);
      assertEquals("c32af7dbc2738947cb197036a770cf9910c7ab9a", hashBuilder.toString());
    }
  }

  @Test
  public void test_givenDirectoryHasEmptyDirectory_whenContentIsHashed_thenItIsIncludedInHash()
      throws IOException, InterruptedException {
    Path testDirPath = Paths.get("contains_empty_dir");
    // Not possible to check in empty dir, so recreate it manually
    filesystem.mkdirs(testDirPath);
    filesystem.mkdirs(testDirPath.resolve("foo"));
    StringBuilder hashBuilder = new StringBuilder();
    Step step =
        new AppleComputeDirectoryContentHashStep(
            hashBuilder, AbsPath.of(filesystemRootPath.resolve(testDirPath)), filesystem);
    step.execute(context);
    assertNotEquals(emptyDataHash(), hashBuilder.toString());
  }

  private static String emptyDataHash() {
    return Hashing.sha1().hashBytes(new byte[] {}).toString();
  }
}
