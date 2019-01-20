/*
 * Copyright 2015-present Facebook, Inc.
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.CodeSignIdentity;
import com.facebook.buck.apple.toolchain.ProvisioningProfileMetadata;
import com.facebook.buck.apple.toolchain.impl.ProvisioningProfileStoreFactory;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.base.Suppliers;
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
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ProvisioningProfileCopyStepTest {
  private Path testdataDir;
  private Path outputFile;
  private Path xcentFile;
  private Path dryRunResultFile;
  private Path entitlementsFile;
  private ProjectFilesystem projectFilesystem;
  private ExecutionContext executionContext;
  private Supplier<ImmutableList<CodeSignIdentity>> codeSignIdentitiesSupplier;

  private static final ImmutableList<String> FAKE_READ_COMMAND = ImmutableList.of("cat");

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Rule public final TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() throws IOException {
    testdataDir = TestDataHelper.getTestDataDirectory(this).resolve("provisioning_profiles");
    projectFilesystem = new FakeProjectFilesystem(testdataDir);
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
    Path tempOutputDir = tmp.getRoot();
    outputFile = tempOutputDir.resolve("embedded.mobileprovision");
    xcentFile = Paths.get("test.xcent");
    dryRunResultFile = Paths.get("test_dry_run_results.plist");
    executionContext = TestExecutionContext.newInstance();
    codeSignIdentitiesSupplier = Suppliers.ofInstance(ImmutableList.of());
    entitlementsFile = testdataDir.resolve("Entitlements.plist");
  }

  @Test
  public void testFailsWithInvalidEntitlementsPlist() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(startsWith("Malformed entitlement .plist: "));

    ProvisioningProfileCopyStep step =
        new ProvisioningProfileCopyStep(
            projectFilesystem,
            testdataDir.resolve("Info.plist"),
            ApplePlatform.IPHONEOS,
            Optional.empty(),
            Optional.of(testdataDir.resolve("Invalid.plist")),
            ProvisioningProfileStoreFactory.fromSearchPath(
                new DefaultProcessExecutor(new TestConsole()), FAKE_READ_COMMAND, testdataDir),
            outputFile,
            xcentFile,
            codeSignIdentitiesSupplier,
            Optional.empty());

    step.execute(executionContext);
  }

  @Test
  public void testFailsWithInvalidInfoPlist() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    thrown.expect(IOException.class);
    thrown.expectMessage(containsString("not a property list"));

    ProvisioningProfileCopyStep step =
        new ProvisioningProfileCopyStep(
            projectFilesystem,
            testdataDir.resolve("Invalid.plist"),
            ApplePlatform.IPHONEOS,
            Optional.empty(),
            Optional.empty(),
            ProvisioningProfileStoreFactory.fromSearchPath(
                new DefaultProcessExecutor(new TestConsole()), FAKE_READ_COMMAND, testdataDir),
            outputFile,
            xcentFile,
            codeSignIdentitiesSupplier,
            Optional.empty());

    step.execute(executionContext);
  }

  @Test
  public void testFailsWithNoSuitableProfilesFound() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "No valid non-expired provisioning profiles match for *.com.example.TestApp");

    Path emptyDir =
        TestDataHelper.getTestDataDirectory(this).resolve("provisioning_profiles_empty");

    ProvisioningProfileCopyStep step =
        new ProvisioningProfileCopyStep(
            projectFilesystem,
            testdataDir.resolve("Info.plist"),
            ApplePlatform.IPHONEOS,
            Optional.empty(),
            Optional.empty(),
            ProvisioningProfileStoreFactory.fromSearchPath(
                new DefaultProcessExecutor(new TestConsole()), FAKE_READ_COMMAND, emptyDir),
            outputFile,
            xcentFile,
            codeSignIdentitiesSupplier,
            Optional.empty());

    step.execute(executionContext);
  }

  @Test
  public void testDoesNotFailInDryRunMode() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    Path emptyDir =
        TestDataHelper.getTestDataDirectory(this).resolve("provisioning_profiles_empty");

    ProvisioningProfileCopyStep step =
        new ProvisioningProfileCopyStep(
            projectFilesystem,
            testdataDir.resolve("Info.plist"),
            ApplePlatform.IPHONEOS,
            Optional.empty(),
            Optional.empty(),
            ProvisioningProfileStoreFactory.fromSearchPath(
                new DefaultProcessExecutor(new TestConsole()), FAKE_READ_COMMAND, emptyDir),
            outputFile,
            xcentFile,
            codeSignIdentitiesSupplier,
            Optional.of(dryRunResultFile));

    Future<Optional<ProvisioningProfileMetadata>> profileFuture =
        step.getSelectedProvisioningProfileFuture();
    step.execute(executionContext);
    assertTrue(profileFuture.isDone());
    assertNotNull(profileFuture.get());
    assertFalse(profileFuture.get().isPresent());

    Optional<String> resultContents = projectFilesystem.readFileIfItExists(dryRunResultFile);
    assertTrue(resultContents.isPresent());
    NSDictionary resultPlist =
        (NSDictionary) PropertyListParser.parse(resultContents.get().getBytes(Charsets.UTF_8));

    assertEquals(new NSString("com.example.TestApp"), resultPlist.get("bundle-id"));
  }

  @Test
  public void shouldSetProvisioningProfileFutureWhenStepIsRun() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProvisioningProfileCopyStep step =
        new ProvisioningProfileCopyStep(
            projectFilesystem,
            testdataDir.resolve("Info.plist"),
            ApplePlatform.IPHONEOS,
            Optional.empty(),
            Optional.empty(),
            ProvisioningProfileStoreFactory.fromSearchPath(
                new DefaultProcessExecutor(new TestConsole()), FAKE_READ_COMMAND, testdataDir),
            outputFile,
            xcentFile,
            codeSignIdentitiesSupplier,
            Optional.empty());

    Future<Optional<ProvisioningProfileMetadata>> profileFuture =
        step.getSelectedProvisioningProfileFuture();
    step.execute(executionContext);
    assertTrue(profileFuture.isDone());
    assertNotNull(profileFuture.get());
  }

  @Test
  public void testNoEntitlementsDoesNotMergeInvalidProfileKeys() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProvisioningProfileCopyStep step =
        new ProvisioningProfileCopyStep(
            projectFilesystem,
            testdataDir.resolve("Info.plist"),
            ApplePlatform.IPHONEOS,
            Optional.of("00000000-0000-0000-0000-000000000000"),
            Optional.empty(),
            ProvisioningProfileStoreFactory.fromSearchPath(
                new DefaultProcessExecutor(new TestConsole()), FAKE_READ_COMMAND, testdataDir),
            outputFile,
            xcentFile,
            codeSignIdentitiesSupplier,
            Optional.empty());
    step.execute(executionContext);

    ProvisioningProfileMetadata selectedProfile =
        step.getSelectedProvisioningProfileFuture().get().get();
    ImmutableMap<String, NSObject> profileEntitlements = selectedProfile.getEntitlements();
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
            ApplePlatform.IPHONEOS,
            Optional.of("00000000-0000-0000-0000-000000000000"),
            Optional.of(entitlementsFile),
            ProvisioningProfileStoreFactory.fromSearchPath(
                new DefaultProcessExecutor(new TestConsole()), FAKE_READ_COMMAND, testdataDir),
            outputFile,
            xcentFile,
            codeSignIdentitiesSupplier,
            Optional.empty());
    step.execute(executionContext);

    ProvisioningProfileMetadata selectedProfile =
        step.getSelectedProvisioningProfileFuture().get().get();
    ImmutableMap<String, NSObject> profileEntitlements = selectedProfile.getEntitlements();
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
            ApplePlatform.IPHONEOS,
            Optional.of("00000000-0000-0000-0000-000000000000"),
            Optional.of(entitlementsFile),
            ProvisioningProfileStoreFactory.fromSearchPath(
                new DefaultProcessExecutor(new TestConsole()), FAKE_READ_COMMAND, testdataDir),
            outputFile,
            xcentFile,
            codeSignIdentitiesSupplier,
            Optional.empty());
    step.execute(executionContext);

    ProvisioningProfileMetadata selectedProfile =
        step.getSelectedProvisioningProfileFuture().get().get();
    ImmutableMap<String, NSObject> profileEntitlements = selectedProfile.getEntitlements();
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
            ApplePlatform.IPHONEOS,
            Optional.of("00000000-0000-0000-0000-000000000000"),
            Optional.of(entitlementsFile),
            ProvisioningProfileStoreFactory.fromSearchPath(
                new DefaultProcessExecutor(new TestConsole()), FAKE_READ_COMMAND, testdataDir),
            outputFile,
            xcentFile,
            codeSignIdentitiesSupplier,
            Optional.empty());
    step.execute(executionContext);

    Optional<String> xcentContents = projectFilesystem.readFileIfItExists(xcentFile);
    assertTrue(xcentContents.isPresent());
    NSDictionary xcentPlist =
        (NSDictionary) PropertyListParser.parse(xcentContents.get().getBytes());
    assertEquals(
        xcentPlist.get("application-identifier"), new NSString("ABCDE12345.com.example.TestApp"));
  }
}
