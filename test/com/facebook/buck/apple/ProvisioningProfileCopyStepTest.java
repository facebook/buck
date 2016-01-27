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

import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.Future;

public class ProvisioningProfileCopyStepTest {
  private Path testdataDir;
  private Path tempOutputDir;
  private Path outputFile;
  private Path xcentFile;
  private ProjectFilesystem projectFilesystem;
  private ExecutionContext executionContext;
  private CodeSignIdentityStore codeSignIdentityStore;

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() throws IOException {
    testdataDir = TestDataHelper.getTestDataDirectory(this).resolve("provisioning_profiles");
    projectFilesystem = new FakeProjectFilesystem(testdataDir.toFile());
    Files.walkFileTree(
        testdataDir,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            projectFilesystem.writeBytesToPath(
                Files.readAllBytes(file),
                projectFilesystem.resolve(file));
            return FileVisitResult.CONTINUE;
          }
        });
    DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();
    tmp.create();
    tempOutputDir = tmp.getRootPath();
    outputFile = tempOutputDir.resolve("embedded.mobileprovision");
    xcentFile = Paths.get("test.xcent");
    executionContext = TestExecutionContext.newInstance();
    codeSignIdentityStore =
        CodeSignIdentityStore.fromIdentities(ImmutableList.<CodeSignIdentity>of());

  }

  @Test
  public void testFailsWithInvalidEntitlementsPlist() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(startsWith("Malformed entitlement .plist: "));

    ProvisioningProfileCopyStep step = new ProvisioningProfileCopyStep(
        projectFilesystem,
        testdataDir.resolve("Info.plist"),
        Optional.<String>absent(),
        Optional.<Path>of(testdataDir.resolve("Invalid.plist")),
        ProvisioningProfileStore.fromSearchPath(testdataDir),
        outputFile,
        xcentFile,
        codeSignIdentityStore
    );

    step.execute(executionContext);
  }

  @Test
  public void testFailsWithInvalidInfoPlist() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(startsWith("Unable to get bundle ID from info.plist"));

    ProvisioningProfileCopyStep step = new ProvisioningProfileCopyStep(
        projectFilesystem,
        testdataDir.resolve("Invalid.plist"),
        Optional.<String>absent(),
        Optional.<Path>absent(),
        ProvisioningProfileStore.fromSearchPath(testdataDir),
        outputFile,
        xcentFile,
        codeSignIdentityStore
    );

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

    ProvisioningProfileCopyStep step = new ProvisioningProfileCopyStep(
        projectFilesystem,
        testdataDir.resolve("Info.plist"),
        Optional.<String>absent(),
        Optional.<Path>absent(),
        ProvisioningProfileStore.fromSearchPath(emptyDir),
        outputFile,
        xcentFile,
        codeSignIdentityStore
    );

    step.execute(executionContext);
  }

  @Test
  public void shouldSetProvisioningProfileFutureWhenStepIsRun() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProvisioningProfileCopyStep step = new ProvisioningProfileCopyStep(
        projectFilesystem,
        testdataDir.resolve("Info.plist"),
        Optional.<String>absent(),
        Optional.<Path>absent(),
        ProvisioningProfileStore.fromSearchPath(testdataDir),
        outputFile,
        xcentFile,
        codeSignIdentityStore
    );

    Future<ProvisioningProfileMetadata> profileFuture = step.getSelectedProvisioningProfileFuture();
    step.execute(executionContext);
    assertTrue(profileFuture.isDone());
    assertNotNull(profileFuture.get());
  }
}
