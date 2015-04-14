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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
  public void testBuildHeadersFlavorDirectly() throws IOException {
    // build an intermediate #headers rule.
    workspace.runBuckBuild("//Libraries/EXExample:EXExample#headers").assertSuccess();

    // Verify the contents of the headers directory.
    String pathToHeaders =
        "buck-out/bin/Libraries/EXExample/__EXExample#headers_public_headers__";
    File headersDirectory = workspace.getFile(pathToHeaders);
    assertTrue(
        "public header should be available in " + headersDirectory + ".",
        new File(headersDirectory, "EXExample/EXExampleModel.h").exists());
    assertFalse(
        "non-public header should not be available in " + headersDirectory + ".",
        new File(headersDirectory, "EXExample/NSString+Palindrome.h").exists());
  }

  @Test
  @SuppressWarnings("PMD.LooseCoupling") // ArrayList.class
  public void testCreateCompilationDatabaseForAppleLibraryWithNoDeps() throws IOException {
    // buck build the #compilation-database.
    File compilationDatabase = workspace.buildAndReturnOutput(
        "//Libraries/EXExample:EXExample#compilation-database,iphonesimulator-x86_64");
    assertEquals(
        Paths.get("buck-out/gen/Libraries/EXExample/" +
            "__EXExample#compilation-database,iphonesimulator-x86_64.json"),
        tmp.getRootPath().relativize(compilationDatabase.toPath()));

    // Parse the compilation_database.json file.
    ObjectMapper mapper = new ObjectMapper();
    JavaType type = mapper.getTypeFactory().
        constructCollectionType(ArrayList.class, JsonSerializableDatabaseEntry.class);
    List<JsonSerializableDatabaseEntry> entries = mapper.readValue(compilationDatabase, type);
    Map<String, JsonSerializableDatabaseEntry> fileToEntry = Maps.newHashMap();
    for (JsonSerializableDatabaseEntry entry : entries) {
      fileToEntry.put(entry.file, entry);
    }

    Iterable<String> frameworks = ImmutableList.of(
        Paths.get("/System/Library/Frameworks/Foundation.framework").getParent().toString());
    String pathToPrivateHeaders = "buck-out/gen/Libraries/EXExample/" +
        "EXExample#header-symlink-tree,iphonesimulator-x86_64";
    String pathToPublicHeaders = "buck-out/gen/Libraries/EXExample/" +
        "EXExample#exported-header-symlink-tree,iphonesimulator-x86_64";
    Iterable<String> includes = ImmutableList.of(pathToPrivateHeaders, pathToPublicHeaders);

    // Verify the entries in the compilation database.
    assertFlags(
        "Libraries/EXExample/EXExample/EXExampleModel.m",
        "buck-out/bin/Libraries/EXExample/EXExample#compile-pic-EXExample_" +
            "EXExampleModel.m.o,iphonesimulator-x86_64/EXExample/EXExampleModel.m.o",
        /* isLibrary */ true,
        fileToEntry,
        frameworks,
        includes);
    assertFlags(
        "Libraries/EXExample/EXExample/EXUser.mm",
        "buck-out/bin/Libraries/EXExample/EXExample#compile-pic-EXExample_" +
            "EXUser.mm.o,iphonesimulator-x86_64/EXExample/EXUser.mm.o",
        /* isLibrary */ true,
        fileToEntry,
        frameworks,
        includes);
    assertFlags(
        "Libraries/EXExample/EXExample/Categories/NSString+Palindrome.m",
        "buck-out/bin/Libraries/EXExample/EXExample#compile-pic-EXExample_" +
            "Categories_NSString_Palindrome.m.o,iphonesimulator-x86_64/" +
            "EXExample/Categories/NSString+Palindrome.m.o",
        /* isLibrary */ true,
        fileToEntry,
        frameworks,
        includes);
  }

  @Test
  @SuppressWarnings("PMD.LooseCoupling") // ArrayList.class
  public void testCreateCompilationDatabaseForAppleBinaryWithDeps() throws IOException {
    // buck build the #compilation-database.
    File compilationDatabase = workspace.buildAndReturnOutput(
        "//Apps/Weather:Weather#iphonesimulator-x86_64,compilation-database");
    assertEquals(
        Paths.get("buck-out/gen/Apps/Weather/" +
            "__Weather#compilation-database,iphonesimulator-x86_64.json"),
        tmp.getRootPath().relativize(compilationDatabase.toPath()));

    // Parse the compilation_database.json file.
    ObjectMapper mapper = new ObjectMapper();
    JavaType type = mapper.getTypeFactory().
        constructCollectionType(ArrayList.class, JsonSerializableDatabaseEntry.class);
    List<JsonSerializableDatabaseEntry> entries = mapper.readValue(compilationDatabase, type);
    Map<String, JsonSerializableDatabaseEntry> fileToEntry = Maps.newHashMap();
    for (JsonSerializableDatabaseEntry entry : entries) {
      fileToEntry.put(entry.file, entry);
    }

    Iterable<String> frameworks = ImmutableList.of(
        Paths.get("/System/Library/Frameworks/Foundation.framework").getParent().toString(),
        Paths.get("/System/Library/Frameworks/UIKit.framework").getParent().toString());
    String pathToPrivateHeaders = "buck-out/gen/Apps/Weather/" +
        "Weather#header-symlink-tree,iphonesimulator-x86_64";
    String pathToPublicHeaders = "buck-out/gen/Libraries/" +
        "EXExample/EXExample#exported-header-symlink-tree,iphonesimulator-x86_64";
    Iterable<String> includes = ImmutableList.of(pathToPrivateHeaders, pathToPublicHeaders);

    assertFlags(
        "Apps/Weather/Weather/EXViewController.m",
        "buck-out/bin/Apps/Weather/Weather#compile-Weather_" +
            "EXViewController.m.o,iphonesimulator-x86_64/Weather/EXViewController.m.o",
        /* isLibrary */ false,
        fileToEntry,
        frameworks,
        includes);
    assertFlags(
        "Apps/Weather/Weather/main.m",
        "buck-out/bin/Apps/Weather/Weather#compile-Weather_" +
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
      Map<String, JsonSerializableDatabaseEntry> fileToEntry,
      Iterable<String> additionalFrameworks,
      Iterable<String> includes) throws IOException {
    String key = tmp.getRootPath().resolve(source).toRealPath().toString();
    JsonSerializableDatabaseEntry entry = fileToEntry.get(key);
    assertNotNull("There should be an entry for " + key + ".", entry);

    String clang = tmp.getRootPath()
        .resolve(XCODE_DEVELOPER_DIR)
        .resolve("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang")
        .toRealPath().toString();
    String sdkRoot = tmp.getRootPath()
        .resolve(XCODE_DEVELOPER_DIR)
        .resolve("Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk")
        .toRealPath().toString();
    String language = "objective-c";
    String languageStandard = "-std=gnu11";
    if (source.endsWith(".mm")) {
      language = "objective-c++";
      languageStandard = "-std=c++11";
      clang += "++";
    }
    List<String> commandArgs = Lists.newArrayList(
        clang,
        "-isysroot",
        sdkRoot,
        "-arch",
        "x86_64",
        "'-mios-simulator-version-min=8.0'",
        "-c",
        "-x",
        language);
    if (isLibrary) {
      commandArgs.add("-fPIC");
    }

    commandArgs.add("'" + languageStandard + "'");
    commandArgs.add("-Wno-deprecated");
    commandArgs.add("-Wno-conversion");

    // TODO(user, jakubzika): It seems like a bug that this set of flags gets inserted twice.
    // Perhaps this has something to do with how the [cxx] section in .buckconfig is processed.
    commandArgs.add("'" + languageStandard + "'");
    commandArgs.add("-Wno-deprecated");
    commandArgs.add("-Wno-conversion");

    for (String include : includes) {
      commandArgs.add("-I");
      commandArgs.add(include);
    }

    for (String framework : additionalFrameworks) {
      commandArgs.add("-F");
      commandArgs.add(sdkRoot + framework);
    }

    commandArgs.add("-o");
    commandArgs.add(output);
    commandArgs.add(source);
    MoreAsserts.assertIterablesEquals(commandArgs, ImmutableList.copyOf(entry.command.split(" ")));
  }

  @VisibleForTesting
  @SuppressFieldNotInitialized
  static class JsonSerializableDatabaseEntry {

    public String directory;
    public String file;
    public String command;

    /** Empty constructor will be used by Jackson. */
    public JsonSerializableDatabaseEntry() {}

    public JsonSerializableDatabaseEntry(String directory, String file, String command) {
      this.directory = directory;
      this.file = file;
      this.command = command;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof JsonSerializableDatabaseEntry)) {
        return false;
      }

      JsonSerializableDatabaseEntry that = (JsonSerializableDatabaseEntry) obj;
      return Objects.equal(this.directory, that.directory) &&
          Objects.equal(this.file, that.file) &&
          Objects.equal(this.command, that.command);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(directory, file, command);
    }

    // Useful if CompilationDatabaseTest fails when comparing JsonSerializableDatabaseEntry objects.
    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("directory", directory)
          .add("file", file)
          .add("command", command)
          .toString();
    }
  }
}
