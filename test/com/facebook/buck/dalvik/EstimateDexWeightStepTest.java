/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.dalvik;

import static com.facebook.buck.io.MorePaths.pathWithPlatformSeparators;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.dalvik.EstimateDexWeightStep.DexWeightEstimator;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.classes.FileLike;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;

public class EstimateDexWeightStepTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private DexWeightEstimator dexWeightEstimator =
      new FakeDexWeightEstimator(
          ImmutableMap.<String, Integer>builder()
              .put(pathWithPlatformSeparators("com/example/Foo.class"), 100)
              .put(pathWithPlatformSeparators("com/example/Bar.class"), 250)
              .put(pathWithPlatformSeparators("com/example/subpackage/Baz.class"), 75)
              .build());

  @Test
  public void testExecuteEstimateDexWeightStep() throws InterruptedException, IOException {
    // Create a directory.
    String name = "dir";
    tmp.newFolder(name);

    tmp.newFolder("dir", "com");
    tmp.newFolder("dir", "com", "example");
    tmp.newFolder("dir", "com", "example", "subpackage");

    tmp.newFile(pathWithPlatformSeparators("dir/com/example/Foo.class"));
    tmp.newFile(pathWithPlatformSeparators("dir/com/example/Bar.class"));
    tmp.newFile(pathWithPlatformSeparators("dir/com/example/not_a_class.png"));
    tmp.newFile(pathWithPlatformSeparators("dir/com/example/subpackage/Baz.class"));

    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot());
    ExecutionContext context = TestExecutionContext.newInstance();

    Path pathToJarOrClassesDirectory = Paths.get(name);
    EstimateDexWeightStep step =
        new EstimateDexWeightStep(filesystem, pathToJarOrClassesDirectory, dexWeightEstimator);
    int exitCode = step.execute(context).getExitCode();
    assertEquals(0, exitCode);
    assertEquals(Integer.valueOf(425), step.get());
  }

  @Test(expected = IllegalStateException.class)
  public void testGetBeforeExecuteThrowsException() throws InterruptedException {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    Path pathToJarOrClassesDirectory = Paths.get("out");
    EstimateDexWeightStep step =
        new EstimateDexWeightStep(filesystem, pathToJarOrClassesDirectory, dexWeightEstimator);
    step.get();
  }

  @Test
  public void testGetShortName() throws InterruptedException {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    Path pathToJarOrClassesDirectory = Paths.get("out");
    EstimateDexWeightStep step =
        new EstimateDexWeightStep(filesystem, pathToJarOrClassesDirectory, dexWeightEstimator);
    assertEquals("estimate_dex_weight", step.getShortName());
  }

  @Test
  public void testGetDescription() throws InterruptedException {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    Path pathToJarOrClassesDirectory = Paths.get("out");
    EstimateDexWeightStep step =
        new EstimateDexWeightStep(filesystem, pathToJarOrClassesDirectory, dexWeightEstimator);
    assertEquals("estimate_dex_weight", step.getDescription(TestExecutionContext.newInstance()));
  }

  private static class FakeDexWeightEstimator implements DexWeightEstimator {

    private final Map<String, Integer> relativePathToCostMap;

    public FakeDexWeightEstimator(ImmutableMap<String, Integer> relativePathToCostMap) {
      this.relativePathToCostMap = relativePathToCostMap;
    }

    @Override
    public int getEstimate(FileLike fileLike) throws IOException {
      return Preconditions.checkNotNull(
          relativePathToCostMap.get(pathWithPlatformSeparators(fileLike.getRelativePath())));
    }
  }
}
