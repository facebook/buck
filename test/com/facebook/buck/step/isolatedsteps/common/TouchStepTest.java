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

package com.facebook.buck.step.isolatedsteps.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import org.junit.Rule;
import org.junit.Test;

public class TouchStepTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testGetShortName() {
    Path someFile = Paths.get("a/file.txt");
    TouchStep touchStep = new TouchStep(someFile);
    assertEquals("touch", touchStep.getShortName());
  }

  @Test
  public void testFileGetsCreated() throws IOException, InterruptedException {
    Path path = Paths.get("somefile");
    assertFalse(ProjectFilesystemUtils.exists(tmp.getRoot(), path));
    TouchStep touchStep = new TouchStep(path);
    touchStep.execute(TestExecutionContext.newInstance(tmp.getRoot()));
    path.toFile().exists();
    assertTrue(ProjectFilesystemUtils.exists(tmp.getRoot(), path));
  }

  @Test
  public void testFileLastModifiedTimeUpdated() throws IOException, InterruptedException {
    Path path = Paths.get("somefile");
    ProjectFilesystemUtils.createNewFile(tmp.getRoot(), path);
    ProjectFilesystemUtils.setLastModifiedTime(tmp.getRoot(), path, FileTime.fromMillis(0));
    FileTime lastModifiedTime = ProjectFilesystemUtils.getLastModifiedTime(tmp.getRoot(), path);
    TouchStep touchStep = new TouchStep(path);
    touchStep.execute(TestExecutionContext.newInstance(tmp.getRoot()));
    assertTrue(
        lastModifiedTime.compareTo(ProjectFilesystemUtils.getLastModifiedTime(tmp.getRoot(), path))
            < 0);
  }
}
