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

package com.facebook.buck.apple;

import static com.facebook.buck.cxx.toolchain.CxxFlavorSanitizer.sanitize;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertNotNull;

import com.facebook.buck.cxx.CxxCompilationDatabaseEntry;
import com.facebook.buck.cxx.CxxCompilationDatabaseUtils;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class CompilationDatabaseIntegrationTest {

  /** This is the value of xcode_developer_dir in the .buckconfig for this test. */
  private static final Path XCODE_DEVELOPER_DIR = Paths.get("xcode-developer-dir");

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private ProjectWorkspace workspace;

  @Before
  public void setupWorkspace() throws IOException {
    Platform platform = Platform.detect();
    Assume.assumeTrue(platform == Platform.MACOS);

    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "ios-project", tmp);
    workspace.setUp();

    Path platforms = workspace.getPath("xcode-developer-dir/Platforms");
    Path sdk = platforms.resolve("iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk");
    Files.createSymbolicLink(sdk.getParent().resolve("iPhoneOS8.0.sdk"), sdk.getFileName());
    sdk = platforms.resolve("iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk");
    Files.createSymbolicLink(sdk.getParent().resolve("iPhoneSimulator8.0.sdk"), sdk.getFileName());
  }

  @Test
  public void testCreateCompilationDatabaseForAppleLibraryWithNoDeps()
      throws InterruptedException, IOException {
    // buck build the #compilation-database.
    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Libraries/EXExample:EXExample#compilation-database,iphonesimulator-x86_64");
    Path compilationDatabase = workspace.buildAndReturnOutput(target.getFullyQualifiedName());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Parse the compilation_database.json file.
    Map<String, CxxCompilationDatabaseEntry> fileToEntry =
        CxxCompilationDatabaseUtils.parseCompilationDatabaseJsonFile(compilationDatabase);

    String pathToPrivateHeaders =
        BuildTargets.getGenPath(
                filesystem,
                target.withFlavors(
                    InternalFlavor.of("iphonesimulator-x86_64"),
                    CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
                "%s.hmap")
            .toString();
    String pathToPublicHeaders =
        BuildTargets.getGenPath(
                filesystem,
                target.withFlavors(
                    CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR,
                    CxxPlatformUtils.getHeaderModeForDefaultPlatform(tmp.getRoot()).getFlavor()),
                "%s.hmap")
            .toString();
    Iterable<String> includes =
        ImmutableList.of(
            pathToPrivateHeaders,
            pathToPublicHeaders,
            filesystem.getBuckPaths().getBuckOut().toString());

    // Verify the entries in the compilation database.
    assertFlags(
        filesystem,
        "Libraries/EXExample/EXExample/EXExampleModel.m",
        target.withFlavors(
            InternalFlavor.of("iphonesimulator-x86_64"),
            InternalFlavor.of("compile-pic-" + sanitize("EXExample/EXExampleModel.m.o"))),
        Paths.get("EXExample/EXExampleModel.m.o"),
        /* isLibrary */ true,
        fileToEntry,
        includes);
    assertFlags(
        filesystem,
        "Libraries/EXExample/EXExample/EXUser.mm",
        target.withFlavors(
            InternalFlavor.of("iphonesimulator-x86_64"),
            InternalFlavor.of("compile-pic-" + sanitize("EXExample/EXUser.mm.o"))),
        Paths.get("EXExample/EXUser.mm.o"),
        /* isLibrary */ true,
        fileToEntry,
        includes);
    assertFlags(
        filesystem,
        "Libraries/EXExample/EXExample/Categories/NSString+Palindrome.m",
        target.withFlavors(
            InternalFlavor.of("iphonesimulator-x86_64"),
            InternalFlavor.of(
                "compile-pic-" + sanitize("EXExample/Categories/NSString+Palindrome.m.o"))),
        Paths.get("EXExample/Categories/NSString+Palindrome.m.o"),
        /* isLibrary */ true,
        fileToEntry,
        includes);
  }

  @Test
  public void testCreateCompilationDatabaseForAppleBinaryWithDeps()
      throws InterruptedException, IOException {
    // buck build the #compilation-database.
    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Apps/Weather:Weather#iphonesimulator-x86_64,compilation-database");
    Path compilationDatabase = workspace.buildAndReturnOutput(target.getFullyQualifiedName());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Parse the compilation_database.json file.
    Map<String, CxxCompilationDatabaseEntry> fileToEntry =
        CxxCompilationDatabaseUtils.parseCompilationDatabaseJsonFile(compilationDatabase);

    String pathToPrivateHeaders =
        BuildTargets.getGenPath(
                filesystem,
                target.withFlavors(
                    InternalFlavor.of("iphonesimulator-x86_64"),
                    CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
                "%s.hmap")
            .toString();
    String pathToPublicHeaders =
        BuildTargets.getGenPath(
                filesystem,
                BuildTargetFactory.newInstance("//Libraries/EXExample:EXExample")
                    .withAppendedFlavors(
                        CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR,
                        CxxPlatformUtils.getHeaderModeForDefaultPlatform(tmp.getRoot())
                            .getFlavor()),
                "%s.hmap")
            .toString();
    Iterable<String> includes =
        ImmutableList.of(
            pathToPrivateHeaders,
            pathToPublicHeaders,
            filesystem.getBuckPaths().getBuckOut().toString());

    assertFlags(
        filesystem,
        "Apps/Weather/Weather/EXViewController.m",
        target.withFlavors(
            InternalFlavor.of("iphonesimulator-x86_64"),
            InternalFlavor.of("compile-" + sanitize("Weather/EXViewController.m.o"))),
        Paths.get("Weather/EXViewController.m.o"),
        /* isLibrary */ false,
        fileToEntry,
        includes);
    assertFlags(
        filesystem,
        "Apps/Weather/Weather/main.m",
        target.withFlavors(
            InternalFlavor.of("iphonesimulator-x86_64"),
            InternalFlavor.of("compile-" + sanitize("Weather/main.m.o"))),
        Paths.get("Weather/main.m.o"),
        /* isLibrary */ false,
        fileToEntry,
        includes);
  }

  private void assertFlags(
      ProjectFilesystem filesystem,
      String source,
      BuildTarget outputTarget,
      Path outputPath,
      boolean isLibrary,
      Map<String, CxxCompilationDatabaseEntry> fileToEntry,
      Iterable<String> includes)
      throws IOException {
    Path tmpRoot = tmp.getRoot().toRealPath();
    String key = tmpRoot.resolve(source).toString();
    CxxCompilationDatabaseEntry entry = fileToEntry.get(key);
    assertNotNull("There should be an entry for " + key + ".", entry);

    Path xcodeDeveloperDir = tmpRoot.resolve(XCODE_DEVELOPER_DIR);
    Path platformDir = xcodeDeveloperDir.resolve("Platforms/iPhoneSimulator.platform");
    Path sdkRoot = platformDir.resolve("Developer/SDKs/iPhoneSimulator.sdk");
    String clang =
        xcodeDeveloperDir.resolve("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang").toString();
    String language = "objective-c";
    String languageStandard = "-std=gnu11";
    if (source.endsWith(".mm")) {
      language = "objective-c++";
      languageStandard = "-std=c++11";
      clang += "++";
    }
    ImmutableList<String> commandArgs1 =
        ImmutableList.of(languageStandard, "-Wno-deprecated", "-Wno-conversion");

    ImmutableList<String> commandArgs2 =
        ImmutableList.of(
            "-isysroot",
            sdkRoot.toString(),
            "-arch",
            "x86_64",
            "-mios-simulator-version-min=8.0",
            "-iquote",
            tmpRoot.toString());

    List<String> commandArgs = new ArrayList<>();
    commandArgs.add(clang);
    commandArgs.add("-x");
    commandArgs.add(language);
    if (isLibrary) {
      commandArgs.add("-fPIC");
      commandArgs.add("-fPIC");
    }

    // TODO(coneko, jakubzika): It seems like a bug that this set of flags gets inserted twice.
    // Perhaps this has something to do with how the [cxx] section in .buckconfig is processed.
    // (Err, it's probably adding both the preprocessor and regular rule command suffixes. Should
    // be harmless.)
    commandArgs.addAll(commandArgs2);
    commandArgs.addAll(commandArgs1);
    commandArgs.addAll(commandArgs2);

    for (String include : includes) {
      commandArgs.add("-I");
      commandArgs.add(include);
    }

    String output =
        BuildTargets.getGenPath(filesystem, outputTarget, "%s").resolve(outputPath).toString();
    commandArgs.add("-Xclang");
    commandArgs.add("-fdebug-compilation-dir");
    commandArgs.add("-Xclang");
    commandArgs.add("." + Strings.repeat("/", 399));
    commandArgs.add("-c");
    commandArgs.add("-MD");
    commandArgs.add("-MF");
    commandArgs.add(output + ".dep");
    commandArgs.add(source);
    commandArgs.add("-o");
    commandArgs.add(output);
    assertThat(
        RichStream.from(entry.getArguments())
            .filter(c -> !c.contains("-fdebug-prefix-map"))
            .toImmutableList(),
        equalTo(commandArgs));
  }
}
