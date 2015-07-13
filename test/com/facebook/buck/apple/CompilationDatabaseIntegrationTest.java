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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.facebook.buck.cxx.CxxCompilationDatabaseEntry;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
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

import java.io.File;
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
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();
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
    Files.createSymbolicLink(sdk.getParent().resolve("iPhoneOS8.0.sdk"), sdk);
    sdk = platforms.resolve("iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk");
    Files.createSymbolicLink(sdk.getParent().resolve("iPhoneSimulator8.0.sdk"), sdk);
  }

  @Test
  public void testCreateCompilationDatabaseForAppleLibraryWithNoDeps() throws IOException {
    // buck build the #compilation-database.
    File compilationDatabase = workspace.buildAndReturnOutput(
        "//Libraries/EXExample:EXExample#compilation-database,iphonesimulator-x86_64");
    assertEquals(
        Paths.get("buck-out/gen/Libraries/EXExample/" +
            "__EXExample#compilation-database,iphonesimulator-x86_64.json"),
        tmp.getRootPath().relativize(compilationDatabase.toPath()));

    // Parse the compilation_database.json file.
    Map<String, CxxCompilationDatabaseEntry> fileToEntry =
        CxxCompilationDatabaseEntry.parseCompilationDatabaseJsonFile(compilationDatabase);

    ImmutableSet<String> frameworks = ImmutableSet.of(
        Paths.get("/System/Library/Frameworks/Foundation.framework").getParent().toString());
    String pathToPrivateHeaders = "buck-out/gen/Libraries/EXExample/" +
        "EXExample#header-symlink-tree,iphonesimulator-x86_64";
    String pathToPublicHeaders = "buck-out/gen/Libraries/EXExample/" +
        "EXExample#exported-header-symlink-tree,iphonesimulator-x86_64";
    Iterable<String> includes = ImmutableList.of(pathToPrivateHeaders, pathToPublicHeaders);

    // Verify the entries in the compilation database.
    assertFlags(
        "Libraries/EXExample/EXExample/EXExampleModel.m",
        "buck-out/gen/Libraries/EXExample/EXExample#compile-pic-EXExample_" +
            "EXExampleModel.m.o,iphonesimulator-x86_64/EXExample/EXExampleModel.m.o",
        /* isLibrary */ true,
        fileToEntry,
        frameworks,
        includes);
    assertFlags(
        "Libraries/EXExample/EXExample/EXUser.mm",
        "buck-out/gen/Libraries/EXExample/EXExample#compile-pic-EXExample_" +
            "EXUser.mm.o,iphonesimulator-x86_64/EXExample/EXUser.mm.o",
        /* isLibrary */ true,
        fileToEntry,
        frameworks,
        includes);
    assertFlags(
        "Libraries/EXExample/EXExample/Categories/NSString+Palindrome.m",
        "buck-out/gen/Libraries/EXExample/EXExample#compile-pic-EXExample_" +
            "Categories_NSString_Palindrome.m.o,iphonesimulator-x86_64/" +
            "EXExample/Categories/NSString+Palindrome.m.o",
        /* isLibrary */ true,
        fileToEntry,
        frameworks,
        includes);
  }

  @Test
  public void testCreateCompilationDatabaseForAppleBinaryWithDeps() throws IOException {
    // buck build the #compilation-database.
    File compilationDatabase = workspace.buildAndReturnOutput(
        "//Apps/Weather:Weather#iphonesimulator-x86_64,compilation-database");
    assertEquals(
        Paths.get("buck-out/gen/Apps/Weather/" +
            "__Weather#compilation-database,iphonesimulator-x86_64.json"),
        tmp.getRootPath().relativize(compilationDatabase.toPath()));

    // Parse the compilation_database.json file.
    Map<String, CxxCompilationDatabaseEntry> fileToEntry =
        CxxCompilationDatabaseEntry.parseCompilationDatabaseJsonFile(compilationDatabase);

    ImmutableSet<String> frameworks = ImmutableSet.of(
        Paths.get("/System/Library/Frameworks/Foundation.framework").getParent().toString(),
        Paths.get("/System/Library/Frameworks/UIKit.framework").getParent().toString());
    String pathToPrivateHeaders = "buck-out/gen/Apps/Weather/" +
        "Weather#header-symlink-tree,iphonesimulator-x86_64";
    String pathToPublicHeaders = "buck-out/gen/Libraries/" +
        "EXExample/EXExample#exported-header-symlink-tree,iphonesimulator-x86_64";
    Iterable<String> includes = ImmutableList.of(pathToPrivateHeaders, pathToPublicHeaders);

    assertFlags(
        "Apps/Weather/Weather/EXViewController.m",
        "buck-out/gen/Apps/Weather/Weather#compile-Weather_" +
            "EXViewController.m.o,iphonesimulator-x86_64/Weather/EXViewController.m.o",
        /* isLibrary */ false,
        fileToEntry,
        frameworks,
        includes);
    assertFlags(
        "Apps/Weather/Weather/main.m",
        "buck-out/gen/Apps/Weather/Weather#compile-Weather_" +
            "main.m.o,iphonesimulator-x86_64/Weather/main.m.o",
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
    Path tmpRoot = tmp.getRootPath().toRealPath();
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
    List<String> commandArgs = Lists.newArrayList();
    commandArgs.add(clang);
    commandArgs.add("'" + languageStandard + "'");
    commandArgs.add("-Wno-deprecated");
    commandArgs.add("-Wno-conversion");

    if (isLibrary) {
      commandArgs.add("-fPIC");
    }

    commandArgs.add("-isysroot");
    commandArgs.add(sdkRoot);
    commandArgs.add("-arch");
    commandArgs.add("x86_64");
    commandArgs.add("'-mios-simulator-version-min=8.0'");

    // TODO(user, jakubzika): It seems like a bug that this set of flags gets inserted twice.
    // Perhaps this has something to do with how the [cxx] section in .buckconfig is processed.
    // (Err, it's probably adding both the preprocessor and regular rule command suffixes. Should
    // be harmless.)
    commandArgs.add("'" + languageStandard + "'");
    commandArgs.add("-Wno-deprecated");
    commandArgs.add("-Wno-conversion");

    commandArgs.add("-arch");
    commandArgs.add("x86_64");

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
    commandArgs.add(source);
    commandArgs.add("-o");
    commandArgs.add(output);
    MoreAsserts.assertIterablesEquals(commandArgs, ImmutableList.copyOf(entry.command.split(" ")));
  }
}
