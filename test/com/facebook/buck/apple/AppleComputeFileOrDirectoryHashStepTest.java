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

public class AppleComputeFileOrDirectoryHashStepTest {

  private Path filesystemRootPath;
  private ProjectFilesystem filesystem;
  private StepExecutionContext context;

  @Before
  public void setUp() {
    assumeTrue(Platform.detect() == Platform.MACOS);
    filesystemRootPath =
        TestDataHelper.getTestDataDirectory(this).resolve("compute_file_or_directory_hash");
    filesystem = TestProjectFilesystems.createProjectFilesystem(filesystemRootPath);
    context = TestExecutionContext.newInstance();
  }

  private static String FOO_FILE_HASH = "4e1243bd22c66e76c2ba9eddc1f91394e57f9f83";

  @Test
  public void
      test_givenMachoUuidNotUsed_givenFileIsNotMachoBinary_thenNoPrefixIsAddedToComputedSha1Hash()
          throws IOException, InterruptedException {
    StringBuilder hashBuilder = new StringBuilder();
    AbsPath testFilePath = AbsPath.of(filesystemRootPath.resolve("foo"));
    Step step =
        new AppleComputeFileOrDirectoryHashStep(
            hashBuilder, testFilePath, filesystem, false, false);
    step.execute(context);
    assertEquals(FOO_FILE_HASH, hashBuilder.toString());
  }

  @Test
  public void
      test_givenMachoUuidNotUsed_givenFileIsMachoBinary_thenNoPrefixIsAddedToComputedSha1Hash()
          throws IOException, InterruptedException {
    StringBuilder hashBuilder = new StringBuilder();
    AbsPath testFilePath = AbsPath.of(filesystemRootPath.resolve("bar"));
    AppleComputeFileOrDirectoryHashStep step =
        new AppleComputeFileOrDirectoryHashStep(
            hashBuilder, testFilePath, filesystem, false, false);
    step.execute(context);
    assertEquals("4b6891845922afb60f019e7ec9e8a216689aa750", hashBuilder.toString());
  }

  @Test
  public void
      test_givenMachoUuidIsUsed_givenFileIsNotMachoBinary_thenPrefixIsAddedToComputedSha1Hash()
          throws IOException, InterruptedException {
    StringBuilder hashBuilder = new StringBuilder();
    AbsPath testFilePath = AbsPath.of(filesystemRootPath.resolve("foo"));
    Step step =
        new AppleComputeFileOrDirectoryHashStep(hashBuilder, testFilePath, filesystem, true, false);
    step.execute(context);
    assertEquals("sha1:" + FOO_FILE_HASH, hashBuilder.toString());
  }

  @Test
  public void test_givenMachoUuidIsUsed_givenFileIsMachoBinary_thenUuidFromBinaryIsUsedAsHash()
      throws IOException, InterruptedException {
    StringBuilder hashBuilder = new StringBuilder();
    AbsPath testFilePath = AbsPath.of(filesystemRootPath.resolve("bar"));
    Step step =
        new AppleComputeFileOrDirectoryHashStep(hashBuilder, testFilePath, filesystem, true, false);
    step.execute(context);
    assertEquals("uuid:24896723764e73118173419116bb4477", hashBuilder.toString());
  }

  @Test
  public void
      test_givenFileIsSymlink_givenDoNotFollowSymlinksIsOn_thenResultHashIsEqualToHashOfResolvedSymlinkPath()
          throws IOException, InterruptedException {
    StringBuilder hashBuilder = new StringBuilder();
    AbsPath testFilePath = AbsPath.of(filesystemRootPath.resolve("symlink"));
    Step step =
        new AppleComputeFileOrDirectoryHashStep(hashBuilder, testFilePath, filesystem, false, true);
    step.execute(context);
    assertEquals("0beec7b5ea3f0fdbc95d0dd47f3c5bc275da8a33", hashBuilder.toString());
  }

  @Test
  public void
      test_givenFileIsSymlink_givenDoNotFollowSymlinkIsOff_thenResultHashIsEqualToContentHash()
          throws IOException, InterruptedException {
    StringBuilder hashBuilder = new StringBuilder();
    AbsPath testFilePath = AbsPath.of(filesystemRootPath.resolve("symlink"));
    Step step =
        new AppleComputeFileOrDirectoryHashStep(
            hashBuilder, testFilePath, filesystem, false, false);
    step.execute(context);
    assertEquals(FOO_FILE_HASH, hashBuilder.toString());
  }

  @Test
  public void test_givenFileIsDirectory_givenDirectoryIsEmpty_thenContentHashIsSameAsOfEmptyData()
      throws IOException, InterruptedException {
    StringBuilder hashBuilder = new StringBuilder();
    Path testDirPath = Paths.get("empty_dir");
    // Not possible to check in empty dir, so recreate it manually
    filesystem.mkdirs(testDirPath);
    Step step =
        new AppleComputeFileOrDirectoryHashStep(
            hashBuilder,
            AbsPath.of(filesystemRootPath.resolve(testDirPath)),
            filesystem,
            false,
            false);
    step.execute(context);
    assertEquals(emptyDataHash(), hashBuilder.toString());
  }

  private static String REFERENCE_DIRECTORY_WITH_FILE_HASH =
      "b11a6313ece4816dbd2e59ffd801e434f7794029";

  @Test
  public void
      test_givenFileIsDirectory_givenDirectoryHasSingleFile_thenContentHashDependsBothOnFileNameAndContent()
          throws IOException, InterruptedException {
    {
      StringBuilder hashBuilder = new StringBuilder();
      Path testDirPath = Paths.get("both_file_name_and_content_matters/reference");
      Step step =
          new AppleComputeFileOrDirectoryHashStep(
              hashBuilder,
              AbsPath.of(filesystemRootPath.resolve(testDirPath)),
              filesystem,
              false,
              false);
      step.execute(context);
      assertEquals(REFERENCE_DIRECTORY_WITH_FILE_HASH, hashBuilder.toString());
    }
    {
      StringBuilder hashBuilder = new StringBuilder();
      Path testDirPath = Paths.get("both_file_name_and_content_matters/different_file_name");
      Step step =
          new AppleComputeFileOrDirectoryHashStep(
              hashBuilder,
              AbsPath.of(filesystemRootPath.resolve(testDirPath)),
              filesystem,
              false,
              false);
      step.execute(context);
      assertEquals("596a8ca78e69ae15a9b6b9a3e9d3d04933dfc9fe", hashBuilder.toString());
    }
    {
      StringBuilder hashBuilder = new StringBuilder();
      Path testDirPath = Paths.get("both_file_name_and_content_matters/different_file_content");
      Step step =
          new AppleComputeFileOrDirectoryHashStep(
              hashBuilder,
              AbsPath.of(filesystemRootPath.resolve(testDirPath)),
              filesystem,
              false,
              false);
      step.execute(context);
      assertEquals("9c63c837303af43d5c119ef0d6acc54e75c649a4", hashBuilder.toString());
    }
  }

  @Test
  public void
      test_givenFileIsDirectory_givenDoNotFollowSymlinksIsOn_givenDirectoryHasSymlink_thenResolvedSymlinkPathIsHashed()
          throws IOException, InterruptedException {
    {
      StringBuilder hashBuilder = new StringBuilder();
      Path testDirPath = Paths.get("with_symlink/reference/dir");
      Step step =
          new AppleComputeFileOrDirectoryHashStep(
              hashBuilder,
              AbsPath.of(filesystemRootPath.resolve(testDirPath)),
              filesystem,
              false,
              true);
      step.execute(context);
      assertEquals("02862c1db9826281328f00a539b99de0d1d420f0", hashBuilder.toString());
    }
    {
      StringBuilder hashBuilder = new StringBuilder();
      Path testDirPath = Paths.get("with_symlink/pointee_changed/dir");
      Step step =
          new AppleComputeFileOrDirectoryHashStep(
              hashBuilder,
              AbsPath.of(filesystemRootPath.resolve(testDirPath)),
              filesystem,
              false,
              true);
      step.execute(context);
      assertEquals("02862c1db9826281328f00a539b99de0d1d420f0", hashBuilder.toString());
    }
    {
      StringBuilder hashBuilder = new StringBuilder();
      Path testDirPath = Paths.get("with_symlink/pointer_changed/dir");
      Step step =
          new AppleComputeFileOrDirectoryHashStep(
              hashBuilder,
              AbsPath.of(filesystemRootPath.resolve(testDirPath)),
              filesystem,
              false,
              true);
      step.execute(context);
      assertEquals("afa1e0a2fb1afdec3e6560bf3ade53eda4ad1c9f", hashBuilder.toString());
    }
  }

  @Test
  public void
      test_givenFileIsDirectory_givenDoNotFollowSymlinksIsOff_givenDirectoryHasSymlink_thenFilesReferencedBySymlinkIsHashed()
          throws IOException, InterruptedException {
    {
      StringBuilder hashBuilder = new StringBuilder();
      Path testDirPath = Paths.get("with_symlink/reference/dir");
      Step step =
          new AppleComputeFileOrDirectoryHashStep(
              hashBuilder,
              AbsPath.of(filesystemRootPath.resolve(testDirPath)),
              filesystem,
              false,
              false);
      step.execute(context);
      assertEquals("1ca54b62e7a19288a7514c119feeab453b08f116", hashBuilder.toString());
    }
    {
      StringBuilder hashBuilder = new StringBuilder();
      Path testDirPath = Paths.get("with_symlink/pointee_changed/dir");
      Step step =
          new AppleComputeFileOrDirectoryHashStep(
              hashBuilder,
              AbsPath.of(filesystemRootPath.resolve(testDirPath)),
              filesystem,
              false,
              false);
      step.execute(context);
      assertEquals("c2b93b480d4c27eb336bc23dda9880e46490ea3e", hashBuilder.toString());
    }
    {
      StringBuilder hashBuilder = new StringBuilder();
      Path testDirPath = Paths.get("with_symlink/pointer_changed/dir");
      Step step =
          new AppleComputeFileOrDirectoryHashStep(
              hashBuilder,
              AbsPath.of(filesystemRootPath.resolve(testDirPath)),
              filesystem,
              false,
              false);
      step.execute(context);
      assertEquals("1ca54b62e7a19288a7514c119feeab453b08f116", hashBuilder.toString());
    }
  }

  @Test
  public void
      test_givenFileIsSymlinkToDirectory_givenDoNotFollowSymlinksIsOff_thenHashIsSameAsForThatDirectory()
          throws IOException, InterruptedException {
    StringBuilder hashBuilder = new StringBuilder();
    Path testDirPath = Paths.get("symlink_to_dir");
    Step step =
        new AppleComputeFileOrDirectoryHashStep(
            hashBuilder,
            AbsPath.of(filesystemRootPath.resolve(testDirPath)),
            filesystem,
            false,
            false);
    step.execute(context);
    assertEquals(REFERENCE_DIRECTORY_WITH_FILE_HASH, hashBuilder.toString());
  }

  @Test
  public void test_givenFileIsDirectory_givenDirectoryHasEmptyDirectory_thenItIsIncludedInHash()
      throws IOException, InterruptedException {
    Path testDirPath = Paths.get("contains_empty_dir");
    // Not possible to check in empty dir, so recreate it manually
    filesystem.mkdirs(testDirPath);
    filesystem.mkdirs(testDirPath.resolve("foo"));
    StringBuilder hashBuilder = new StringBuilder();
    Step step =
        new AppleComputeFileOrDirectoryHashStep(
            hashBuilder,
            AbsPath.of(filesystemRootPath.resolve(testDirPath)),
            filesystem,
            false,
            false);
    step.execute(context);
    assertNotEquals(emptyDataHash(), hashBuilder.toString());
  }

  @Test
  public void
      test_givenFileIsSymlinkToSymlink_givenDoNotFollowSymlinksIsOff_thenHashIsSameAsForRealFile()
          throws IOException, InterruptedException {
    StringBuilder hashBuilder = new StringBuilder();
    Step step =
        new AppleComputeFileOrDirectoryHashStep(
            hashBuilder,
            AbsPath.of(filesystemRootPath.resolve(Paths.get("symlink_to_symlink"))),
            filesystem,
            false,
            false);
    step.execute(context);
    assertEquals(FOO_FILE_HASH, hashBuilder.toString());
  }

  private static String emptyDataHash() {
    return Hashing.sha1().hashBytes(new byte[] {}).toString();
  }
}
