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

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.cxx.toolchain.objectfile.LcUuidContentsScrubber;
import com.facebook.buck.io.file.FileScrubber;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Test suite for LcUuidContentsScrubber. Ensures that we can 1) correctly modify UUIDs of Mach-O
 * files and 2) deterministically generate such UUIDs.
 *
 * <p>Determinism is required to guarantee the same output for link rules.
 */
public class LcUuidContentsScrubberTest {

  private static final String LIB_HELLO_DYLIB_UUID = "16EA1424-1912-38E2-8D0D-D6BB9C45040C";
  private static final Pattern DWARFDUMP_UUID_PATTERN =
      Pattern.compile("UUID: (?<uuid>(?:[A-Z0-9]|-)+) \\([a-z0-9_]+\\) .*", Pattern.DOTALL);

  private Path testDataDir;

  private Path getHelloLibDylibPath() {
    return testDataDir.resolve("uuid_contents_scrubber").resolve("libHelloLib.dylib");
  }

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    testDataDir = TestDataHelper.getTestDataDirectory(this);
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "uuid_contents_scrubber", tmp);
    workspace.setUp();
  }

  private static void scrubUuidOf(Path path, boolean scrubConcurrently)
      throws IOException, FileScrubber.ScrubException {
    try (FileChannel file =
        FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      LcUuidContentsScrubber scrubber = new LcUuidContentsScrubber(scrubConcurrently);
      scrubber.scrubFile(file);
    }
  }

  private Optional<String> getUuidOf(Path path) throws IOException, InterruptedException {
    String uuidOutput =
        workspace.runCommand("dwarfdump", "--uuid", path.toString()).getStdout().get();
    Matcher matcher = DWARFDUMP_UUID_PATTERN.matcher(uuidOutput);
    if (matcher.matches()) {
      String uuid = matcher.group("uuid");
      return Optional.ofNullable(uuid);
    }

    return Optional.empty();
  }

  @Test
  public void testScrubberModifiesUuid()
      throws IOException, InterruptedException, FileScrubber.ScrubException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    // Copy the source dylib, so we can scrub the temporary copy
    Path srcDylibPath = getHelloLibDylibPath();
    Optional<String> uuid = getUuidOf(srcDylibPath);
    assertTrue(uuid.isPresent());
    assertEquals(uuid.get(), LIB_HELLO_DYLIB_UUID);

    Path destFolder = tmp.newFolder();
    Path destDylibPath = destFolder.resolve(srcDylibPath.getFileName());
    destDylibPath = Files.copy(srcDylibPath, destDylibPath);

    scrubUuidOf(destDylibPath, false);
    Optional<String> destUuid = getUuidOf(destDylibPath);
    assertTrue(destUuid.isPresent());

    assertNotEquals(uuid.get(), destUuid.get());
  }

  @Test
  public void testDeterministicScrubber()
      throws IOException, FileScrubber.ScrubException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    testScrubberDeterminism(false, false);
  }

  @Test
  public void testDeterministicConcurrentScrubber()
      throws IOException, FileScrubber.ScrubException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    testScrubberDeterminism(false, true);
  }

  private void testScrubberDeterminism(
      boolean scrubFirstCopyConcurrently, boolean scrubSecondCopyConcurrently)
      throws IOException, FileScrubber.ScrubException, InterruptedException {
    Path srcDylibPath = getHelloLibDylibPath();

    final Path destFolder = tmp.newFolder();
    Path firstCopyDylibPath = destFolder.resolve(srcDylibPath.getFileName() + "1");
    Path secondCopyDylibPath = destFolder.resolve(srcDylibPath.getFileName() + "2");

    firstCopyDylibPath = Files.copy(srcDylibPath, firstCopyDylibPath);
    secondCopyDylibPath = Files.copy(srcDylibPath, secondCopyDylibPath);

    scrubUuidOf(firstCopyDylibPath, scrubFirstCopyConcurrently);
    Optional<String> firstUuid = getUuidOf(firstCopyDylibPath);
    assertTrue(firstUuid.isPresent());

    scrubUuidOf(secondCopyDylibPath, scrubSecondCopyConcurrently);
    Optional<String> secondUuid = getUuidOf(secondCopyDylibPath);
    assertTrue(secondUuid.isPresent());

    assertEquals(firstUuid.get(), secondUuid.get());
  }
}
