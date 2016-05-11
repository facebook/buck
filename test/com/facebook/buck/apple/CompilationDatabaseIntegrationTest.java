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

import static com.facebook.buck.cxx.CxxFlavorSanitizer.sanitize;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertNotNull;

import com.facebook.buck.cxx.CxxCompilationDatabaseEntry;
import com.facebook.buck.cxx.CxxCompilationDatabaseUtils;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class CompilationDatabaseIntegrationTest {

  /** This is the value of xcode_developer_dir in the .buckconfig for this test. */
  private static final Path XCODE_DEVELOPER_DIR = Paths.get("xcode-developer-dir");

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();
  private ProjectWorkspace workspace;

  @Before
  public void setupWorkspace() throws IOException {
    Platform platform = Platform.detect();
    Assume.assumeTrue(platform == Platform.MACOS);

    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "ios-project", tmp);
    workspace.setUp();

    Path platforms = workspace.getPath("xcode-developer-dir/Platforms");
    Path sdk = platforms.resolve("iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk");
    Files.createSymbolicLink(sdk.getParent().resolve("iPhoneOS8.0.sdk"), sdk.getFileName());
    sdk = platforms.resolve("iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk");
    Files.createSymbolicLink(sdk.getParent().resolve("iPhoneSimulator8.0.sdk"), sdk.getFileName());
  }

  @Test
  public void testCreateCompilationDatabaseForAppleLibraryWithNoDeps() throws IOException {
    // buck build the #compilation-database.
    BuildTarget target = BuildTargetFactory.newInstance(
        "//Libraries/EXExample:EXExample#compilation-database,iphonesimulator-x86_64");
    Path compilationDatabase = workspace.buildAndReturnOutput(target.getFullyQualifiedName());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Parse the compilation_database.json file.
    Map<String, CxxCompilationDatabaseEntry> fileToEntry =
        CxxCompilationDatabaseUtils.parseCompilationDatabaseJsonFile(compilationDatabase);

    ImmutableSet<String> frameworks = ImmutableSet.of(
        Paths.get("/System/Library/Frameworks/Foundation.framework").getParent().toString());
    String pathToPrivateHeaders =
        BuildTargets
            .getGenPath(
                filesystem,
                target.withFlavors(
                    ImmutableFlavor.of("iphonesimulator-x86_64"),
                    CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
                "%s.hmap")
            .toString();
    String pathToPublicHeaders =
        BuildTargets
            .getGenPath(
                filesystem,
                target.withFlavors(
                    ImmutableFlavor.of("iphonesimulator-x86_64"),
                    CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR),
                "%s.hmap")
            .toString();
    Iterable<String> includes = ImmutableList.of(
        pathToPrivateHeaders,
        pathToPublicHeaders,
        filesystem.getBuckPaths().getBuckOut().toString());

    // Verify the entries in the compilation database.
    assertFlags(
        "Libraries/EXExample/EXExample/EXExampleModel.m",
        BuildTargets
            .getGenPath(
                filesystem,
                target.withFlavors(
                    ImmutableFlavor.of("iphonesimulator-x86_64"),
                    ImmutableFlavor.of("compile-pic-" + sanitize("EXExample/EXExampleModel.m.o"))),
                "%s")
            .resolve("EXExample/EXExampleModel.m.o")
            .toString(),
        /* isLibrary */ true,
        fileToEntry,
        frameworks,
        includes);
    assertFlags(
        "Libraries/EXExample/EXExample/EXUser.mm",
        BuildTargets
            .getGenPath(
                filesystem,
                target.withFlavors(
                    ImmutableFlavor.of("iphonesimulator-x86_64"),
                    ImmutableFlavor.of("compile-pic-" + sanitize("EXExample/EXUser.mm.o"))),
                "%s")
            .resolve("EXExample/EXUser.mm.o")
            .toString(),
        /* isLibrary */ true,
        fileToEntry,
        frameworks,
        includes);
    assertFlags(
        "Libraries/EXExample/EXExample/Categories/NSString+Palindrome.m",
        BuildTargets
            .getGenPath(
                filesystem,
                target.withFlavors(
                    ImmutableFlavor.of("iphonesimulator-x86_64"),
                    ImmutableFlavor.of(
                        "compile-pic-" +
                            sanitize("EXExample/Categories/NSString+Palindrome.m.o"))),
                "%s")
            .resolve("EXExample/Categories/NSString+Palindrome.m.o")
            .toString(),
        /* isLibrary */ true,
        fileToEntry,
        frameworks,
        includes);
  }

  @Test
  public void testCreateCompilationDatabaseForAppleBinaryWithDeps() throws IOException {
    // buck build the #compilation-database.
    BuildTarget target = BuildTargetFactory.newInstance(
        "//Apps/Weather:Weather#iphonesimulator-x86_64,compilation-database");
    Path compilationDatabase = workspace.buildAndReturnOutput(target.getFullyQualifiedName());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Parse the compilation_database.json file.
    Map<String, CxxCompilationDatabaseEntry> fileToEntry =
        CxxCompilationDatabaseUtils.parseCompilationDatabaseJsonFile(compilationDatabase);

    ImmutableSet<String> frameworks = ImmutableSet.of(
        Paths.get("/System/Library/Frameworks/Foundation.framework").getParent().toString(),
        Paths.get("/System/Library/Frameworks/UIKit.framework").getParent().toString());
    String pathToPrivateHeaders =
        BuildTargets
            .getGenPath(
                filesystem,
                target.withFlavors(
                    ImmutableFlavor.of("iphonesimulator-x86_64"),
                    CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
                "%s.hmap")
            .toString();
    String pathToPublicHeaders =
        BuildTargets
            .getGenPath(
                filesystem,
                BuildTargetFactory.newInstance(
                    "//Libraries/EXExample:EXExample#iphonesimulator-x86_64," +
                        CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR),
                "%s.hmap")
            .toString();
    Iterable<String> includes = ImmutableList.of(
        pathToPrivateHeaders,
        pathToPublicHeaders,
        filesystem.getBuckPaths().getBuckOut().toString());

    assertFlags(
        "Apps/Weather/Weather/EXViewController.m",
        BuildTargets
            .getGenPath(
                filesystem,
                target.withFlavors(
                    ImmutableFlavor.of("iphonesimulator-x86_64"),
                    ImmutableFlavor.of(
                        "compile-" + sanitize("Weather/EXViewController.m.o"))),
                "%s")
            .resolve("Weather/EXViewController.m.o")
            .toString(),
        /* isLibrary */ false,
        fileToEntry,
        frameworks,
        includes);
    assertFlags(
        "Apps/Weather/Weather/main.m",
        BuildTargets
            .getGenPath(
                filesystem,
                target.withFlavors(
                    ImmutableFlavor.of("iphonesimulator-x86_64"),
                    ImmutableFlavor.of(
                        "compile-" + sanitize("Weather/main.m.o"))),
                "%s")
            .resolve("Weather/main.m.o")
            .toString(),
        /* isLibrary */ false,
        fileToEntry,
        frameworks,
        includes);
  }

  private void assertFlags(
      String source,
      String output,
      boolean isLibrary,
      Map<String, CxxCompilationDatabaseEntry> fileToEntry,
      ImmutableSet<String> additionalFrameworks,
      Iterable<String> includes) throws IOException {
    Path tmpRoot = tmp.getRoot().toRealPath();
    String key = tmpRoot.resolve(source).toString();
    CxxCompilationDatabaseEntry entry = fileToEntry.get(key);
    assertNotNull("There should be an entry for " + key + ".", entry);

    String clang = tmpRoot
        .resolve(XCODE_DEVELOPER_DIR)
        .resolve("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang")
        .toString();
    String sdkRoot = tmpRoot
        .resolve(XCODE_DEVELOPER_DIR)
        .resolve("Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk")
        .toString();
    String language = "objective-c";
    String languageStandard = "-std=gnu11";
    if (source.endsWith(".mm")) {
      language = "objective-c++";
      languageStandard = "-std=c++11";
      clang += "++";
    }
    ImmutableList<String> commandArgs1 = ImmutableList.of(
        "'" + languageStandard + "'",
        "-Wno-deprecated",
        "-Wno-conversion");

    ImmutableList<String> commandArgs2 = ImmutableList.of(
        "-isysroot",
        sdkRoot,
        "-arch",
        "x86_64",
        "'-mios-simulator-version-min=8.0'");

    List<String> commandArgs = Lists.newArrayList();
    commandArgs.add(clang);
    if (isLibrary) {
      commandArgs.add("-fPIC");
      commandArgs.add("-fPIC");
    }

    // TODO(Coneko, k21): It seems like a bug that this set of flags gets inserted twice.
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

    for (String framework : additionalFrameworks) {
      commandArgs.add("-F");
      commandArgs.add(sdkRoot + framework);
    }

    commandArgs.add("-Xclang");
    commandArgs.add("-fdebug-compilation-dir");
    commandArgs.add("-Xclang");
    commandArgs.add("." + Strings.repeat("/", 249));
    commandArgs.add("-x");
    commandArgs.add(language);
    commandArgs.add("-c");
    commandArgs.add("-MD");
    commandArgs.add("-MF");
    commandArgs.add(tmpRoot.resolve("dep.tmp").toString());
    commandArgs.add(source);
    commandArgs.add("-o");
    commandArgs.add(output);
    assertThat(ImmutableList.copyOf(entry.getCommand().split(" ")), equalTo(commandArgs));
  }
}
