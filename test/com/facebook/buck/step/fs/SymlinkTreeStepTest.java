/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.step.fs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SymlinkTreeStepTest {

  @Rule public final TemporaryFolder tmpDir = new TemporaryFolder();

  @Test
  public void testSymlinkFiles() throws InterruptedException, IOException {

    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(tmpDir.getRoot().toPath());

    ExecutionContext context = TestExecutionContext.newInstance();

    Path root = Paths.get("symlink-tree-root");

    Path link1 = Paths.get("link");
    Path source1 = Paths.get("source1");
    projectFilesystem.writeContentsToPath("foo", source1);

    Path link2 = Paths.get("a", "link", "under", "directory");
    Path source2 = Paths.get("source2");
    projectFilesystem.writeContentsToPath("bar", source2);

    SymlinkTreeStep step =
        new SymlinkTreeStep(
            "link_tree",
            projectFilesystem,
            root,
            ImmutableMap.of(
                link1, source1,
                link2, source2));

    step.execute(context);

    assertTrue(projectFilesystem.exists(root.resolve(link1)));
    assertEquals(Optional.of("foo"), projectFilesystem.readFirstLine(root.resolve(link1)));

    assertTrue(projectFilesystem.exists(root.resolve(link2)));
    assertEquals(Optional.of("bar"), projectFilesystem.readFirstLine(root.resolve(link2)));

    // Modify the original file and see if the linked file changes as well.
    projectFilesystem.writeContentsToPath("new", source1);
    assertEquals(Optional.of("new"), projectFilesystem.readFirstLine(root.resolve(link1)));
  }
}
