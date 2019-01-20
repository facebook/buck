/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.shell;

import static com.facebook.buck.util.environment.Platform.WINDOWS;
import static java.nio.file.attribute.PosixFilePermission.GROUP_READ;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class MetadataIntegrationTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Rule public ExpectedException exception = ExpectedException.none();

  @Test
  public void testMetadataPermissions() throws InterruptedException, IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "metadata_permissions", temporaryFolder);
    workspace.setUp();

    ProcessResult buildResult = workspace.runBuckCommand("build", "//:test");
    buildResult.assertSuccess();

    // Check permissions on metadata.type
    ProjectFilesystem fs = workspace.asCell().getFilesystem();
    Path metadataType = fs.getBuckPaths().getScratchDir().resolve("metadata.type");
    Set<PosixFilePermission> perms = fs.getPosixFilePermissions(metadataType);
    assertEquals(perms, EnumSet.of(OWNER_READ, OWNER_WRITE, GROUP_READ, OTHERS_READ));

    // As a proxy for being able to read from a build performed by another user, check that we can
    // still build if we remove write permissions from metadata.type.
    Files.setPosixFilePermissions(
        fs.getPathForRelativeExistingPath(metadataType),
        EnumSet.of(OWNER_READ, GROUP_READ, OTHERS_READ));
    ProcessResult buildResult2 = workspace.runBuckCommand("build", "//:test");
    buildResult2.assertSuccess();
  }
}
