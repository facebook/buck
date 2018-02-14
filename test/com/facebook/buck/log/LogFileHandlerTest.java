/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.log;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class LogFileHandlerTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private Path logDir;

  @Before
  public void setUp() throws InterruptedException, IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "LogFileHandlerTest", temporaryFolder);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
    logDir = filesystem.resolve(filesystem.getBuckPaths().getLogDir());
  }

  @Test
  public void testCleanWithMinLogCount() throws IOException {
    Assert.assertEquals(2, countSubDirectories(logDir));
    LogFileHandler.newCleaner(Long.MAX_VALUE, 1, Integer.MAX_VALUE).clean(logDir);
    Assert.assertEquals(2, countSubDirectories(logDir));
  }

  @Test
  public void testCleanByCount() throws IOException {
    Assert.assertEquals(2, countSubDirectories(logDir));
    LogFileHandler.newCleaner(Long.MAX_VALUE, 1, 0).clean(logDir);
    Assert.assertEquals(1, countSubDirectories(logDir));
  }

  @Test
  public void testCleanBySizeBytes() throws IOException {
    Assert.assertEquals(2, countSubDirectories(logDir));
    LogFileHandler.newCleaner(1, Integer.MAX_VALUE, 0).clean(logDir);
    Assert.assertEquals(0, countSubDirectories(logDir));
  }

  @Test
  public void testNothingIsDeletedWhileWithinLimits() throws IOException {
    Assert.assertEquals(2, countSubDirectories(logDir));
    LogFileHandler.newCleaner(Long.MAX_VALUE, Integer.MAX_VALUE, 0).clean(logDir);
    Assert.assertEquals(2, countSubDirectories(logDir));
  }

  private static int countSubDirectories(Path dir) {
    File[] directories = new File(dir.toString()).listFiles(File::isDirectory);

    return directories.length;
  }
}
