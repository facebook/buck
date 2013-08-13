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

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.java.abi.AbiWriterProtocol;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
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

  private ProjectWorkspace workspace;

  @Test
  public void testBuildJavaLibraryWithoutSrcsAndVerifyAbi() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "abi", tmp);
    workspace.setUp();

    // Run `buck build`.
    ProcessResult buildResult = workspace.runBuckCommand("build", "//:no_srcs");
    assertEquals("Successful build should exit with 0.", 0, buildResult.getExitCode());
    File outputFile = workspace.getFile("buck-out/gen/lib__no_srcs__output/no_srcs.jar");
    assertTrue(outputFile.exists());
    // TODO(mbolin): When we produce byte-for-byte identical JAR files across builds, do:
    //
    //   HashCode hashOfOriginalJar = Files.hash(outputFile, Hashing.sha1());
    //
    // And then compare that to the output when //:no_srcs is built again with --no-cache.
    long sizeOfOriginalJar = outputFile.length();

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
    assertTrue(outputFile.isFile());
    assertEquals(
        "The content of the output file will be 'Hello World!' if it is read from the build cache.",
        "Hello world!\n",
        Files.toString(outputFile, Charsets.UTF_8));

    // Run `buck build` yet again, but this time, specify `--no-cache`.
    ProcessResult buildResult3 = workspace.runBuckCommand("build", "--no-cache", "//:no_srcs");
    buildResult3.assertExitCode(0);
    assertNotEquals(
        "The contents of the file should no longer be pulled from the corrupted build cache.",
        "Hello world!\n",
        Files.toString(outputFile, Charsets.UTF_8));
    assertEquals(
        "We cannot do a byte-for-byte comparision with the original JAR because timestamps might " +
        "have changed, but we verify that they are the same size, as a proxy.",
        sizeOfOriginalJar,
        outputFile.length());
  }

  @Test
  public void testFileChangeThatDoesNotModifyAbiAvoidsRebuild() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "rulekey_changed_while_abi_stable", tmp);
    workspace.setUp();

    // Run `buck build`.
    ProcessResult buildResult = workspace.runBuckCommand("build", "//:biz");
    assertEquals("Successful build should exit with 0.", 0, buildResult.getExitCode());

    String utilRuleKey = getContents("buck-out/bin/.success/util");
    String utilRuleKeyNoDeps = getContents("buck-out/gen/lib__util__abi/rule_key_no_deps");
    String utilAbi = getContents("buck-out/gen/lib__util__abi/abi");
    String utilAbiForDeps = getContents("buck-out/gen/lib__util__abi/abi_deps");

    String bizRuleKey = getContents("buck-out/bin/.success/biz");
    String bizRuleKeyNoDeps = getContents("buck-out/gen/lib__biz__abi/rule_key_no_deps");
    String bizAbi = getContents("buck-out/gen/lib__biz__abi/abi");
    String bizAbiForDeps = getContents("buck-out/gen/lib__biz__abi/abi_deps");

    long utilJarSize = workspace.getFile("buck-out/gen/lib__util__output/util.jar").length();
    long bizJarLastModified = workspace.getFile("buck-out/gen/lib__biz__output/biz.jar").lastModified();

    // TODO(mbolin): Run uber-biz.jar and verify it prints "Hello World!\n".

    // Edit Util.java in a way that does not affect its ABI.
    String originalUtilJava = getContents("Util.java");
    String replacementContents = originalUtilJava.replace("Hello World", "Hola Mundo");
    Files.write(replacementContents, workspace.getFile("Util.java"), Charsets.UTF_8);

    // Run `buck build` again.
    ProcessResult buildResult2 = workspace.runBuckCommand("build", "//:biz");
    assertEquals("Successful build should exit with 0.", 0, buildResult2.getExitCode());

    assertThat(utilRuleKey, not(equalTo(getContents("buck-out/bin/.success/util"))));
    assertThat(utilRuleKeyNoDeps, not(equalTo(getContents("buck-out/gen/lib__util__abi/rule_key_no_deps"))));
    assertEquals(utilAbi, getContents("buck-out/gen/lib__util__abi/abi"));
    assertEquals(utilAbiForDeps, getContents("buck-out/gen/lib__util__abi/abi_deps"));

    assertThat(bizRuleKey, not(equalTo(getContents("buck-out/bin/.success/biz"))));
    assertEquals(bizRuleKeyNoDeps, getContents("buck-out/gen/lib__biz__abi/rule_key_no_deps"));
    assertEquals(bizAbi, getContents("buck-out/gen/lib__biz__abi/abi"));
    assertEquals(bizAbiForDeps, getContents("buck-out/gen/lib__biz__abi/abi_deps"));

    assertThat(
        "util.jar should have been rewritten, so its file size should have changed.",
        utilJarSize,
        not(equalTo(workspace.getFile("buck-out/gen/lib__util__output/util.jar").length())));
    assertEquals(
        "biz.jar should not have been rewritten, so its last-modified time should be the same.",
        bizJarLastModified,
        workspace.getFile("buck-out/gen/lib__biz__output/biz.jar").lastModified());

    // TODO(mbolin): Run uber-biz.jar and verify it prints "Hola Mundo!\n".

    // TODO(mbolin): This last scenario that is being tested would be better as a unit test.
    // Run `buck build` one last time. This ensures that a dependency java_library() rule (:util)
    // that is built via BuildRuleSuccess.Type.MATCHING_DEPS_ABI_AND_RULE_KEY_NO_DEPS does not
    // explode when its dependent rule (:biz) invokes the dependency's getAbiKey() method as part of
    // its own getAbiKeyForDeps().
    ProcessResult buildResult3 = workspace.runBuckCommand("build", "//:biz");
    assertEquals("Successful build should exit with 0.", 0, buildResult3.getExitCode());
  }

  /**
   * Asserts that the specified file exists and returns its contents.
   */
  private String getContents(String relativePathToFile) throws IOException {
    File file = workspace.getFile(relativePathToFile);
    assertTrue(relativePathToFile + " should exist and be an ordinary file.", file.exists());
    String content = Strings.nullToEmpty(Files.toString(file, Charsets.UTF_8)).trim();
    assertFalse(relativePathToFile + " should not be empty.", content.isEmpty());
    return content;
  }
}
