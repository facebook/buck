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

package com.facebook.buck.java;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.java.abi.AbiWriterProtocol;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Charsets;
import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

/**
 * Integration test that verifies that a {@link DefaultJavaLibraryRule} writes its ABI key as part
 * of compilation.
 */
public class DefaultJavaLibraryRuleIntegrationTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void testBuildJavaLibraryWithoutSrcsAndVerifyAbi() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "abi", tmp);
    workspace.setUp();

    // Run `buck build`.
    ProcessResult buildResult = workspace.runBuckCommand("build", "//:no_srcs");
    assertEquals("Successful build should exit with 0.", 0, buildResult.getExitCode());

    // This verifies that the ABI key was written correctly.
    workspace.verify();

    // Verify the build cache.
    File buildCache = workspace.getFile("cache_dir");
    assertTrue(buildCache.isDirectory());
    assertEquals("There should be two entries in the build cache.",
        2,
        buildCache.listFiles().length);

    // Verify the ABI key entry in the build cache.
    File[] cacheEntries = buildCache.listFiles();
    File abiKeyEntry = cacheEntries[0].length() == 41 ? cacheEntries[0] : cacheEntries[1];
    assertEquals(AbiWriterProtocol.EMPTY_ABI_KEY,
        Files.readFirstLine(abiKeyEntry, Charsets.UTF_8));

    // Run `buck clean`.
    ProcessResult cleanResult = workspace.runBuckCommand("clean");
    assertEquals("Successful clean should exit with 0.", 0, cleanResult.getExitCode());
    assertEquals("The build cache should still exist.", 2, buildCache.listFiles().length);

    // Corrupt the build cache!
    File outputFileEntry = cacheEntries[0].length() == 41 ? cacheEntries[1] : cacheEntries[0];
    Files.write("Hello world!\n", outputFileEntry, Charsets.UTF_8);

    // Run `buck build` again.
    ProcessResult buildResult2 = workspace.runBuckCommand("build", "//:no_srcs");
    assertEquals("Successful build should exit with 0.", 0, buildResult2.getExitCode());
    File outputFile = workspace.getFile("buck-out/gen/lib__no_srcs__output/no_srcs.jar");
    assertTrue(outputFile.isFile());
    assertEquals(
        "The content of the output file will be 'Hello World!' if it is read from the build cache.",
        "Hello world!\n",
        Files.toString(outputFile, Charsets.UTF_8));
  }
}
