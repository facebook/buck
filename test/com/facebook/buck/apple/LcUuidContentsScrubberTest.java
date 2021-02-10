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
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.cxx.toolchain.objectfile.LcUuidContentsScrubber;
import com.facebook.buck.cxx.toolchain.objectfile.Machos;
import com.facebook.buck.cxx.toolchain.objectfile.ObjectFileScrubbers;
import com.facebook.buck.io.file.FileScrubber;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.nio.ByteBufferUnmapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Assume;
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

  private AbsPath testDataDir;

  private AbsPath getHelloLibDylibPath() {
    return testDataDir.resolve("uuid_contents_scrubber").resolve("libHelloLib.dylib");
  }

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    testDataDir = AbsPath.of(TestDataHelper.getTestDataDirectory(this));
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "uuid_contents_scrubber", tmp);
    workspace.setUp();
  }

  private static void scrubUuidOf(AbsPath path, boolean scrubConcurrently)
      throws IOException, FileScrubber.ScrubException {
    try (FileChannel file =
        FileChannel.open(path.getPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      LcUuidContentsScrubber scrubber = new LcUuidContentsScrubber(scrubConcurrently);
      scrubber.scrubFile(file);
    }
  }

  private Optional<String> getUuidOf(AbsPath path) throws IOException, InterruptedException {
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
  public void testUuidGetter() throws IOException, InterruptedException, Machos.MachoException {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    AbsPath srcDylibPath = getHelloLibDylibPath();
    Optional<String> maybeUuidFromDwarfdump = getUuidOf(srcDylibPath);
    assertTrue(maybeUuidFromDwarfdump.isPresent());
    assertEquals(maybeUuidFromDwarfdump.get(), LIB_HELLO_DYLIB_UUID);

    String cleanUuidFromDwarfdump = maybeUuidFromDwarfdump.get().replace("-", "");

    try (FileChannel file = FileChannel.open(srcDylibPath.getPath(), StandardOpenOption.READ)) {

      try (ByteBufferUnmapper unmapper =
          ByteBufferUnmapper.createUnsafe(
              file.map(FileChannel.MapMode.READ_ONLY, 0, file.size()))) {
        ByteBuffer fileBuffer = unmapper.getByteBuffer();

        Optional<byte[]> maybeUuid = Machos.getUuidIfPresent(fileBuffer);
        assertTrue(maybeUuid.isPresent());

        String hex = ObjectFileScrubbers.bytesToHex(maybeUuid.get(), false);
        assertEquals(hex, cleanUuidFromDwarfdump);
      }
    }
  }

  @Test
  public void testScrubberModifiesUuid()
      throws IOException, InterruptedException, FileScrubber.ScrubException {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    // Copy the source dylib, so we can scrub the temporary copy
    AbsPath srcDylibPath = getHelloLibDylibPath();
    Optional<String> uuid = getUuidOf(srcDylibPath);
    assertTrue(uuid.isPresent());
    assertEquals(uuid.get(), LIB_HELLO_DYLIB_UUID);

    AbsPath destFolder = tmp.newFolder();
    AbsPath destDylibPath = destFolder.resolve(srcDylibPath.getPath().getFileName());
    destDylibPath = AbsPath.of(Files.copy(srcDylibPath.getPath(), destDylibPath.getPath()));

    scrubUuidOf(destDylibPath, false);
    Optional<String> destUuid = getUuidOf(destDylibPath);
    assertTrue(destUuid.isPresent());

    assertNotEquals(uuid.get(), destUuid.get());
  }

  @Test
  public void testDeterministicScrubber()
      throws IOException, FileScrubber.ScrubException, InterruptedException {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    testScrubberDeterminism(false, false);
  }

  @Test
  public void testDeterministicConcurrentScrubber()
      throws IOException, FileScrubber.ScrubException, InterruptedException {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    testScrubberDeterminism(false, true);
  }

  private void testScrubberDeterminism(
      boolean scrubFirstCopyConcurrently, boolean scrubSecondCopyConcurrently)
      throws IOException, FileScrubber.ScrubException, InterruptedException {
    AbsPath srcDylibPath = getHelloLibDylibPath();

    final AbsPath destFolder = tmp.newFolder();
    AbsPath firstCopyDylibPath = destFolder.resolve(srcDylibPath.getPath().getFileName() + "1");
    AbsPath secondCopyDylibPath = destFolder.resolve(srcDylibPath.getPath().getFileName() + "2");

    firstCopyDylibPath =
        AbsPath.of(Files.copy(srcDylibPath.getPath(), firstCopyDylibPath.getPath()));
    secondCopyDylibPath =
        AbsPath.of(Files.copy(srcDylibPath.getPath(), secondCopyDylibPath.getPath()));

    scrubUuidOf(firstCopyDylibPath, scrubFirstCopyConcurrently);
    Optional<String> firstUuid = getUuidOf(firstCopyDylibPath);
    assertTrue(firstUuid.isPresent());

    scrubUuidOf(secondCopyDylibPath, scrubSecondCopyConcurrently);
    Optional<String> secondUuid = getUuidOf(secondCopyDylibPath);
    assertTrue(secondUuid.isPresent());

    assertEquals(firstUuid.get(), secondUuid.get());
  }
}
