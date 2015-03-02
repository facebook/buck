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

import com.facebook.buck.apple.CompilationDatabase.JsonSerializableDatabaseEntry;
import com.facebook.buck.apple.clang.HeaderMap;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

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
        "//Libraries/EXExample:EXExample#compilation-database");
    assertEquals(
        Paths.get("buck-out/gen/Libraries/EXExample/" +
            "__EXExample#compilation-database_compilation_database.json"),
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

    // Verify the entries in the compilation database.
    String iquoteArg = tmp.getRootPath().resolve(
        "buck-out/bin/Libraries/EXExample/__my_EXExample#compilation-database__.hmap")
        .toRealPath()
        .toString();
    assertFlags(
        "Libraries/EXExample/EXExample/EXExampleModel.h",
        fileToEntry,
        /* additionalFrameworks */ ImmutableList.<String>of(),
        /* includes */ ImmutableList.<String>of(),
        iquoteArg);
    assertFlags(
        "Libraries/EXExample/EXExample/EXExampleModel.m",
        fileToEntry,
        /* additionalFrameworks */ ImmutableList.<String>of(),
        /* includes */ ImmutableList.<String>of(),
        iquoteArg);
    assertFlags(
        "Libraries/EXExample/EXExample/EXUser.mm",
        fileToEntry,
        /* additionalFrameworks */ ImmutableList.<String>of(),
        /* includes */ ImmutableList.<String>of(),
        iquoteArg);
    assertFlags(
        "Libraries/EXExample/EXExample/Categories/NSString+Palindrome.h",
        fileToEntry,
        /* additionalFrameworks */ ImmutableList.<String>of(),
        /* includes */ ImmutableList.<String>of(),
        iquoteArg);
    assertFlags(
        "Libraries/EXExample/EXExample/Categories/NSString+Palindrome.m",
        fileToEntry,
        /* additionalFrameworks */ ImmutableList.<String>of(),
        /* includes */ ImmutableList.<String>of(),
        iquoteArg);

    // Verify the header map specified as the iquote argument.
    HeaderMap headerMap = HeaderMap.loadFromFile(workspace.getFile(iquoteArg));
    assertEquals(
        tmp.getRootPath().resolve("Libraries/EXExample/EXExample/EXExampleModel.h")
            .toRealPath(),
        Paths.get(headerMap.lookup("EXExampleModel.h")));
    assertEquals(
        tmp.getRootPath().resolve("Libraries/EXExample/EXExample/Categories/NSString+Palindrome.h")
            .toRealPath(),
        Paths.get(headerMap.lookup("NSString+Palindrome.h")));
  }

  @Test
  @SuppressWarnings("PMD.LooseCoupling") // ArrayList.class
  public void testCreateCompilationDatabaseForAppleBinaryWithDeps() throws IOException {
    // buck build the #compilation-database.
    File compilationDatabase = workspace.buildAndReturnOutput(
        "//Apps/Weather:Weather#compilation-database");
    assertEquals(
        Paths.get("buck-out/gen/Apps/Weather/" +
            "__Weather#compilation-database_compilation_database.json"),
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

    // Verify the entries in the compilation database.
    String iquoteArg = tmp.getRootPath().resolve(
        "buck-out/bin/Apps/Weather/__my_Weather#compilation-database__.hmap")
        .toRealPath()
        .toString();
    String pathToHeaders = "buck-out/bin/Libraries/EXExample/__EXExample#headers_public_headers__";
    assertFlags(
        "Apps/Weather/Weather/EXViewController.h",
        fileToEntry,
        ImmutableList.of("/System/Library/Frameworks/UIKit.framework"),
        ImmutableList.of(pathToHeaders),
        iquoteArg);
    assertFlags(
        "Apps/Weather/Weather/EXViewController.m",
        fileToEntry,
        ImmutableList.of("/System/Library/Frameworks/UIKit.framework"),
        ImmutableList.of(pathToHeaders),
        iquoteArg);
    assertFlags(
        "Apps/Weather/Weather/main.m",
        fileToEntry,
        ImmutableList.of("/System/Library/Frameworks/UIKit.framework"),
        ImmutableList.of(pathToHeaders),
        iquoteArg);
  }

  private void assertFlags(
      String fileName,
      Map<String, JsonSerializableDatabaseEntry> fileToEntry,
      Iterable<String> additionalFrameworks,
      Iterable<String> includes,
      String iquoteArg) throws IOException {
    String key = tmp.getRootPath().resolve(fileName).toRealPath().toString();
    JsonSerializableDatabaseEntry entry = fileToEntry.get(key);
    assertNotNull("There should be an entry for " + key + ".", entry);

    String sdkRoot = tmp.getRootPath()
        .resolve(XCODE_DEVELOPER_DIR)
        .resolve("Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk")
        .toRealPath().toString();
    String language = "objective-c";
    String languageStandard = "-std=gnu99";
    if ("Libraries/EXExample/EXExample/EXUser.mm".equals(fileName)) {
      language = "objective-c++";
      languageStandard = "-std=c++11";
    }
    List<String> commandArgs = Lists.newArrayList(
        "clang",
        "-x",
        language,
        "-arch",
        "i386",
        "-mios-simulator-version-min=7.0",
        "-fmessage-length=0",
        "-fdiagnostics-show-note-include-stack",
        "-fmacro-backtrace-limit=0",
        languageStandard,
        "-fpascal-strings",
        "-fexceptions",
        "-fasm-blocks",
        "-fstrict-aliasing",
        "-fobjc-abi-version=2",
        "-fobjc-legacy-dispatch",
        "-O0",
        "-g",
        "-MMD",
        "-fobjc-arc",
        "-isysroot",
        sdkRoot,
        "-F" + sdkRoot + "/System/Library/Frameworks/Foundation.framework");

    for (String framework : additionalFrameworks) {
      commandArgs.add("-F" + sdkRoot + framework);
    }

    for (String include : includes) {
      commandArgs.add("-I" + tmp.getRootPath().resolve(include).toRealPath());
    }

    commandArgs.addAll(ImmutableList.of(
        "-iquote",
        iquoteArg,
        "-c",
        entry.file));
    MoreAsserts.assertIterablesEquals(commandArgs, ImmutableList.copyOf(entry.command.split(" ")));
  }
}
