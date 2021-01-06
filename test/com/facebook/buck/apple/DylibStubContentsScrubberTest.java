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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.cxx.toolchain.objectfile.DylibStubContentsScrubber;
import com.facebook.buck.cxx.toolchain.objectfile.MachoDyldInfoCommand;
import com.facebook.buck.cxx.toolchain.objectfile.MachoDyldInfoCommandReader;
import com.facebook.buck.cxx.toolchain.objectfile.MachoExportTrieNode;
import com.facebook.buck.cxx.toolchain.objectfile.MachoExportTrieReader;
import com.facebook.buck.io.file.FileScrubber;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class DylibStubContentsScrubberTest {

  private Path testDataDir;

  private Path getHelloLibDylibPath() {
    return testDataDir.resolve("dylib_stub_scrubber").resolve("libHelloLib.dylib");
  }

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    testDataDir = TestDataHelper.getTestDataDirectory(this);
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "dylib_stub_scrubber", tmp);
    workspace.setUp();
  }

  @Test
  public void testScrubber() throws IOException, FileScrubber.ScrubException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    // Copy the source dylib, so we can scrub the temporary copy
    Path srcDylibPath = getHelloLibDylibPath();
    Path destFolder = tmp.newFolder();
    Path destDylibPath = destFolder.resolve(srcDylibPath.getFileName());
    Files.copy(srcDylibPath, destDylibPath);

    FileChannel dylibChannel =
        FileChannel.open(destDylibPath, StandardOpenOption.READ, StandardOpenOption.WRITE);
    DylibStubContentsScrubber scrubber = new DylibStubContentsScrubber();
    scrubber.scrubFile(dylibChannel);

    // Read the DYLD info, so we can get the offset to the export trie + read it
    MappedByteBuffer dylibByteBuffer =
        dylibChannel.map(FileChannel.MapMode.READ_ONLY, 0, dylibChannel.size());
    Optional<MachoDyldInfoCommand> maybeDyldInfo = MachoDyldInfoCommandReader.read(dylibByteBuffer);
    assertTrue(maybeDyldInfo.isPresent());

    dylibByteBuffer.position(maybeDyldInfo.get().getExportInfoOffset());
    ByteBuffer exportInfoBuffer = dylibByteBuffer.slice();
    exportInfoBuffer.limit(maybeDyldInfo.get().getExportInfoSize());

    Optional<MachoExportTrieNode> maybeRoot = MachoExportTrieReader.read(exportInfoBuffer);
    assertTrue(maybeRoot.isPresent());

    List<MachoExportTrieNode> exportedSymbols = maybeRoot.get().collectNodesWithExportInfo();
    assertThat(exportedSymbols.size(), equalTo(2));
    for (MachoExportTrieNode node : exportedSymbols) {
      assertTrue(node.getExportInfo().isPresent());
      assertThat(node.getExportInfo().get().address, equalTo(0L));
    }

    String nmOutput = workspace.runCommand("nm", destDylibPath.toString()).getStdout().get();
    assertFalse(nmOutput.isEmpty());
    assertThat(nmOutput, containsString("0000000000000000 T _goodbye"));
    assertThat(nmOutput, containsString("0000000000000000 T _hello"));
  }
}
