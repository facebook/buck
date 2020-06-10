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

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.cxx.toolchain.objectfile.MachoBindInfoReader;
import com.facebook.buck.cxx.toolchain.objectfile.MachoBindInfoSymbol;
import com.facebook.buck.cxx.toolchain.objectfile.Machos;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.nio.ByteBufferUnmapper;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class MachoDynamicLinkingTest {

  private Path testDataDir;

  private Path getHelloWorldFlatNamespace() {
    return testDataDir.resolve("macho_dynamic_linking").resolve("iOSHelloWorldApp_FlatNamespace");
  }

  private Path getHelloWorldTwoLevelNamespace() {
    return testDataDir
        .resolve("macho_dynamic_linking")
        .resolve("iOSHelloWorldApp_TwoLevelNamespace");
  }

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    testDataDir = TestDataHelper.getTestDataDirectory(this);
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "macho_dynamic_linking", tmp);
    workspace.setUp();
  }

  @Test
  public void testHelloWorldFlatNamespaceContainsBoundSymbols()
      throws IOException, Machos.MachoException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    List<MachoBindInfoSymbol> expectedSymbols = new ArrayList<>();
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "_OBJC_CLASS_$_NSError",
            Optional.empty(),
            MachoBindInfoSymbol.LibraryLookup.FLAT_LOOKUP));
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "_OBJC_METACLASS_$_NSObject",
            Optional.empty(),
            MachoBindInfoSymbol.LibraryLookup.FLAT_LOOKUP));
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "__objc_empty_cache", Optional.empty(), MachoBindInfoSymbol.LibraryLookup.FLAT_LOOKUP));
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "_objc_release", Optional.empty(), MachoBindInfoSymbol.LibraryLookup.FLAT_LOOKUP));
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "_objc_retain", Optional.empty(), MachoBindInfoSymbol.LibraryLookup.FLAT_LOOKUP));
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "__NSConcreteStackBlock",
            Optional.empty(),
            MachoBindInfoSymbol.LibraryLookup.FLAT_LOOKUP));
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "dyld_stub_binder", Optional.empty(), MachoBindInfoSymbol.LibraryLookup.FLAT_LOOKUP));
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "_OBJC_CLASS_$_NSPersistentContainer",
            Optional.empty(),
            MachoBindInfoSymbol.LibraryLookup.FLAT_LOOKUP));
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "_OBJC_CLASS_$_UIApplication",
            Optional.empty(),
            MachoBindInfoSymbol.LibraryLookup.FLAT_LOOKUP));
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "_$s15_ObjectiveCTypes01_A11CBridgeablePTl",
            Optional.empty(),
            MachoBindInfoSymbol.LibraryLookup.FLAT_LOOKUP));
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "_$sSSs21_ObjectiveCBridgeable10FoundationMc",
            Optional.empty(),
            MachoBindInfoSymbol.LibraryLookup.FLAT_LOOKUP));
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "_$sSo8NSObjectCSH10ObjectiveCMc",
            Optional.empty(),
            MachoBindInfoSymbol.LibraryLookup.FLAT_LOOKUP));

    Path twoLevelBinary = getHelloWorldFlatNamespace();
    assertExecutableContainsStronglyBoundSymbols(twoLevelBinary, expectedSymbols);
  }

  @Test
  public void testHelloWorldTwoLevelNamespaceContainsBoundSymbols()
      throws IOException, Machos.MachoException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    List<MachoBindInfoSymbol> expectedSymbols = new ArrayList<>();
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "_OBJC_CLASS_$_NSError",
            Optional.of("/System/Library/Frameworks/Foundation.framework/Foundation"),
            MachoBindInfoSymbol.LibraryLookup.DEPENDENT_LIBRARY));
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "_OBJC_METACLASS_$_NSObject",
            Optional.of("/usr/lib/libobjc.A.dylib"),
            MachoBindInfoSymbol.LibraryLookup.DEPENDENT_LIBRARY));
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "__objc_empty_cache",
            Optional.of("/usr/lib/libobjc.A.dylib"),
            MachoBindInfoSymbol.LibraryLookup.DEPENDENT_LIBRARY));
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "_objc_release",
            Optional.of("/usr/lib/libobjc.A.dylib"),
            MachoBindInfoSymbol.LibraryLookup.DEPENDENT_LIBRARY));
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "_objc_retain",
            Optional.of("/usr/lib/libobjc.A.dylib"),
            MachoBindInfoSymbol.LibraryLookup.DEPENDENT_LIBRARY));
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "__NSConcreteStackBlock",
            Optional.of("/usr/lib/libSystem.B.dylib"),
            MachoBindInfoSymbol.LibraryLookup.DEPENDENT_LIBRARY));
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "dyld_stub_binder",
            Optional.of("/usr/lib/libSystem.B.dylib"),
            MachoBindInfoSymbol.LibraryLookup.DEPENDENT_LIBRARY));
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "_OBJC_CLASS_$_NSPersistentContainer",
            Optional.of("/System/Library/Frameworks/CoreData.framework/CoreData"),
            MachoBindInfoSymbol.LibraryLookup.DEPENDENT_LIBRARY));
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "_OBJC_CLASS_$_UIApplication",
            Optional.of("/System/Library/Frameworks/UIKit.framework/UIKit"),
            MachoBindInfoSymbol.LibraryLookup.DEPENDENT_LIBRARY));
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "_$s15_ObjectiveCTypes01_A11CBridgeablePTl",
            Optional.of("/usr/lib/swift/libswiftCore.dylib"),
            MachoBindInfoSymbol.LibraryLookup.DEPENDENT_LIBRARY));
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "_$sSSs21_ObjectiveCBridgeable10FoundationMc",
            Optional.of("/usr/lib/swift/libswiftFoundation.dylib"),
            MachoBindInfoSymbol.LibraryLookup.DEPENDENT_LIBRARY));
    expectedSymbols.add(
        MachoBindInfoSymbol.of(
            "_$sSo8NSObjectCSH10ObjectiveCMc",
            Optional.of("/usr/lib/swift/libswiftObjectiveC.dylib"),
            MachoBindInfoSymbol.LibraryLookup.DEPENDENT_LIBRARY));

    Path twoLevelBinary = getHelloWorldTwoLevelNamespace();
    assertExecutableContainsStronglyBoundSymbols(twoLevelBinary, expectedSymbols);
  }

  private void assertExecutableContainsStronglyBoundSymbols(
      Path executablePath, Collection<MachoBindInfoSymbol> symbols)
      throws IOException, Machos.MachoException {
    FileChannel file = FileChannel.open(executablePath, StandardOpenOption.READ);
    try (ByteBufferUnmapper unmapper =
        ByteBufferUnmapper.createUnsafe(file.map(FileChannel.MapMode.READ_ONLY, 0, file.size()))) {
      ByteBuffer mappedFile = unmapper.getByteBuffer();

      ImmutableSet<MachoBindInfoSymbol> boundSymbols =
          MachoBindInfoReader.parseStronglyBoundSymbols(mappedFile);

      Set<MachoBindInfoSymbol> expectedSymbols = new HashSet<>();
      expectedSymbols.addAll(symbols);

      // If `expectedSymbols` is a subset, then after computing the intersection, we expect all
      // elements to be still present.
      int sizeBeforeIntersection = expectedSymbols.size();
      expectedSymbols.retainAll(boundSymbols);
      int sizeAfterIntersection = expectedSymbols.size();

      assertThat(sizeBeforeIntersection, equalTo(sizeAfterIntersection));
    }
  }
}
