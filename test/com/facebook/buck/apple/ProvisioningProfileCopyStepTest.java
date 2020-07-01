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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.apple.toolchain.ProvisioningProfileMetadata;
import com.facebook.buck.apple.toolchain.ProvisioningProfileStore;
import com.facebook.buck.apple.toolchain.impl.ProvisioningProfileStoreFactory;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ProvisioningProfileCopyStepTest {
  private Path testdataDir;
  private AbsPath outputFile;
  private Path xcentFile;
  private Path entitlementsFile;
  private FakeProjectFilesystem projectFilesystem;
  private StepExecutionContext executionContext;
  private Supplier<ProvisioningProfileMetadata> selectedProfileSupplier;

  private static final ImmutableList<String> FAKE_READ_COMMAND = ImmutableList.of("cat");

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Rule public final TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() throws IOException {
    testdataDir = TestDataHelper.getTestDataDirectory(this).resolve("provisioning_profiles");
    projectFilesystem = new FakeProjectFilesystem(CanonicalCellName.rootCell(), testdataDir);
    Files.walkFileTree(
        testdataDir,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            projectFilesystem.writeBytesToPath(
                Files.readAllBytes(file), projectFilesystem.resolve(file));
            return FileVisitResult.CONTINUE;
          }
        });
    AbsPath tempOutputDir = tmp.getRoot();
    outputFile = tempOutputDir.resolve("embedded.mobileprovision");
    xcentFile = Paths.get("test.xcent");
    executionContext = TestExecutionContext.newInstance();
    entitlementsFile = testdataDir.resolve("Entitlements.plist");
    selectedProfileSupplier =
        () -> {
          ProvisioningProfileStore store =
              ProvisioningProfileStoreFactory.fromSearchPath(
                  new DefaultProcessExecutor(new TestConsole()), FAKE_READ_COMMAND, testdataDir);
          Optional<ProvisioningProfileMetadata> maybeSelectedProfile =
              store.getProvisioningProfileByUUID("00000000-0000-0000-0000-000000000000");
          assertTrue(maybeSelectedProfile.isPresent());
          return maybeSelectedProfile.get();
        };
  }

  @Test
  public void testNoEntitlementsDoesNotMergeInvalidProfileKeys() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);

    ProvisioningProfileCopyStep step =
        new ProvisioningProfileCopyStep(
            projectFilesystem,
            testdataDir.resolve("Info.plist"),
            Optional.empty(),
            outputFile.getPath(),
            xcentFile,
            false,
            selectedProfileSupplier);
    step.execute(executionContext);

    ImmutableMap<String, NSObject> profileEntitlements =
        selectedProfileSupplier.get().getEntitlements();
    assertTrue(
        profileEntitlements.containsKey(
            "com.apple.developer.icloud-container-development-container-identifiers"));

    Optional<String> xcentContents = projectFilesystem.readFileIfItExists(xcentFile);
    assertTrue(xcentContents.isPresent());
    NSDictionary xcentPlist =
        (NSDictionary) PropertyListParser.parse(xcentContents.get().getBytes());
    assertFalse(
        xcentPlist.containsKey(
            "com.apple.developer.icloud-container-development-container-identifiers"));
    assertEquals(
        xcentPlist.get("com.apple.developer.team-identifier"),
        profileEntitlements.get("com.apple.developer.team-identifier"));
  }

  @Test
  public void testEntitlementsDoesNotMergeInvalidProfileKeys() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProvisioningProfileCopyStep step =
        new ProvisioningProfileCopyStep(
            projectFilesystem,
            testdataDir.resolve("Info.plist"),
            Optional.of(entitlementsFile),
            outputFile.getPath(),
            xcentFile,
            false,
            selectedProfileSupplier);
    step.execute(executionContext);

    ImmutableMap<String, NSObject> profileEntitlements =
        selectedProfileSupplier.get().getEntitlements();
    assertTrue(
        profileEntitlements.containsKey(
            "com.apple.developer.icloud-container-development-container-identifiers"));

    Optional<String> xcentContents = projectFilesystem.readFileIfItExists(xcentFile);
    assertTrue(xcentContents.isPresent());
    NSDictionary xcentPlist =
        (NSDictionary) PropertyListParser.parse(xcentContents.get().getBytes());
    assertFalse(
        xcentPlist.containsKey(
            "com.apple.developer.icloud-container-development-container-identifiers"));
    assertEquals(
        xcentPlist.get("com.apple.developer.team-identifier"),
        profileEntitlements.get("com.apple.developer.team-identifier"));
  }

  @Test
  public void testEntitlementsMergesValidProfileKeys() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProvisioningProfileCopyStep step =
        new ProvisioningProfileCopyStep(
            projectFilesystem,
            testdataDir.resolve("Info.plist"),
            Optional.of(entitlementsFile),
            outputFile.getPath(),
            xcentFile,
            false,
            selectedProfileSupplier);
    step.execute(executionContext);

    ImmutableMap<String, NSObject> profileEntitlements =
        selectedProfileSupplier.get().getEntitlements();
    assertTrue(profileEntitlements.containsKey("get-task-allow"));

    Optional<String> entitlementsContents = projectFilesystem.readFileIfItExists(entitlementsFile);
    assertTrue(entitlementsContents.isPresent());
    NSDictionary entitlementsPlist =
        (NSDictionary) PropertyListParser.parse(entitlementsContents.get().getBytes());
    assertFalse(entitlementsPlist.containsKey("get-task-allow"));

    Optional<String> xcentContents = projectFilesystem.readFileIfItExists(xcentFile);
    assertTrue(xcentContents.isPresent());
    NSDictionary xcentPlist =
        (NSDictionary) PropertyListParser.parse(xcentContents.get().getBytes());
    assertTrue(xcentPlist.containsKey("get-task-allow"));
  }

  @Test
  public void testApplicationIdentifierIsValid() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProvisioningProfileCopyStep step =
        new ProvisioningProfileCopyStep(
            projectFilesystem,
            testdataDir.resolve("Info.plist"),
            Optional.of(entitlementsFile),
            outputFile.getPath(),
            xcentFile,
            false,
            selectedProfileSupplier);
    step.execute(executionContext);

    Optional<String> xcentContents = projectFilesystem.readFileIfItExists(xcentFile);
    assertTrue(xcentContents.isPresent());
    NSDictionary xcentPlist =
        (NSDictionary) PropertyListParser.parse(xcentContents.get().getBytes());
    assertEquals(
        xcentPlist.get("application-identifier"), new NSString("ABCDE12345.com.example.TestApp"));
  }

  @Test
  public void testApplicationIdentifierIsInInfoPlist() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    Path infoPlistPath = testdataDir.resolve("Info.plist");
    ProvisioningProfileCopyStep step =
        new ProvisioningProfileCopyStep(
            projectFilesystem,
            infoPlistPath,
            Optional.empty(),
            outputFile.getPath(),
            xcentFile,
            false,
            selectedProfileSupplier);
    step.execute(executionContext);

    byte[] infoPlistContents = projectFilesystem.getFileBytes(infoPlistPath);
    NSDictionary infoPlist = (NSDictionary) PropertyListParser.parse(infoPlistContents);
    assertEquals(
        infoPlist.get("ApplicationIdentifier"), new NSString("ABCDE12345.com.example.TestApp"));
  }

  @Test
  public void testApplicationIdentifierNotInInfoPlistForFrameworks() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    Path infoPlistPath = testdataDir.resolve("Info_Framework.plist");
    ProvisioningProfileCopyStep step =
        new ProvisioningProfileCopyStep(
            projectFilesystem,
            infoPlistPath,
            Optional.empty(),
            outputFile.getPath(),
            xcentFile,
            false,
            selectedProfileSupplier);
    step.execute(executionContext);

    byte[] infoPlistContents = projectFilesystem.getFileBytes(infoPlistPath);
    NSDictionary infoPlist = (NSDictionary) PropertyListParser.parse(infoPlistContents);
    assertNull(infoPlist.get("ApplicationIdentifier"));
  }

  @Test
  public void testApplicationIdentifierNotInInfoPlistForWatchOSApps() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    Path infoPlistPath = testdataDir.resolve("Info_WatchOS.plist");
    ProvisioningProfileCopyStep step =
        new ProvisioningProfileCopyStep(
            projectFilesystem,
            infoPlistPath,
            Optional.empty(),
            outputFile.getPath(),
            xcentFile,
            false,
            selectedProfileSupplier);
    step.execute(executionContext);

    byte[] infoPlistContents = projectFilesystem.getFileBytes(infoPlistPath);
    NSDictionary infoPlist = (NSDictionary) PropertyListParser.parse(infoPlistContents);
    assertNull(infoPlist.get("ApplicationIdentifier"));
  }
}
