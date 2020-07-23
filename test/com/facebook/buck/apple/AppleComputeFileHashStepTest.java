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
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Test;

public class AppleComputeFileHashStepTest {

  private Path filesystemRootPath;
  private ProjectFilesystem filesystem;
  private StepExecutionContext context;

  @Before
  public void setUp() {
    assumeTrue(Platform.detect() == Platform.MACOS);
    filesystemRootPath = TestDataHelper.getTestDataDirectory(this).resolve("compute_file_hash");
    filesystem = TestProjectFilesystems.createProjectFilesystem(filesystemRootPath);
    context = TestExecutionContext.newInstance();
  }

  @Test
  public void
      test_givenMachoUuidNotUsed_givenFileIsNotMachoBinary_whenHashIsComputed_thenNoPrefixIsAddedToComputedSha1Hash()
          throws IOException, InterruptedException {
    StringBuilder hashBuilder = new StringBuilder();
    AbsPath testFilePath = AbsPath.of(filesystemRootPath.resolve("foo"));
    AppleComputeFileHashStep step =
        new AppleComputeFileHashStep(hashBuilder, testFilePath, false, filesystem);
    step.execute(context);
    assertEquals("4e1243bd22c66e76c2ba9eddc1f91394e57f9f83", hashBuilder.toString());
  }

  @Test
  public void
      test_givenMachoUuidNotUsed_givenFileIsMachoBinary_whenHashIsComputed_thenNoPrefixIsAddedToComputedSha1Hash()
          throws IOException, InterruptedException {
    StringBuilder hashBuilder = new StringBuilder();
    AbsPath testFilePath = AbsPath.of(filesystemRootPath.resolve("bar"));
    AppleComputeFileHashStep step =
        new AppleComputeFileHashStep(hashBuilder, testFilePath, false, filesystem);
    step.execute(context);
    assertEquals("4b6891845922afb60f019e7ec9e8a216689aa750", hashBuilder.toString());
  }

  @Test
  public void
      test_givenMachoUuidIsUsed_givenFileIsNotMachoBinary_whenHashIsComputed_thenPrefixIsAddedToComputedSha1Hash()
          throws IOException, InterruptedException {
    StringBuilder hashBuilder = new StringBuilder();
    AbsPath testFilePath = AbsPath.of(filesystemRootPath.resolve("foo"));
    AppleComputeFileHashStep step =
        new AppleComputeFileHashStep(hashBuilder, testFilePath, true, filesystem);
    step.execute(context);
    assertEquals("sha1:4e1243bd22c66e76c2ba9eddc1f91394e57f9f83", hashBuilder.toString());
  }

  @Test
  public void
      test_givenMachoUuidIsUsed_givenFileIsMachoBinary_whenHashIsComputed_thenUuidFromBinaryIsUsedAsHash()
          throws IOException, InterruptedException {
    StringBuilder hashBuilder = new StringBuilder();
    AbsPath testFilePath = AbsPath.of(filesystemRootPath.resolve("bar"));
    AppleComputeFileHashStep step =
        new AppleComputeFileHashStep(hashBuilder, testFilePath, true, filesystem);
    step.execute(context);
    assertEquals("uuid:24896723764e73118173419116bb4477", hashBuilder.toString());
  }
}
