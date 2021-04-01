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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.CodeSignIdentity;
import com.facebook.buck.apple.toolchain.ProvisioningProfileMetadata;
import com.facebook.buck.apple.toolchain.impl.ProvisioningProfileStoreFactory;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.BuildStepResultHolder;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.function.Supplier;
import org.hamcrest.Matchers;
import org.hamcrest.junit.ExpectedException;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ProvisioningProfileSelectStepTest {
  private Path testdataDir;
  private Path dryRunResultFile;
  private FakeProjectFilesystem projectFilesystem;
  private StepExecutionContext executionContext;
  private Supplier<ImmutableList<CodeSignIdentity>> codeSignIdentitiesSupplier;

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
    dryRunResultFile = Paths.get("test_dry_run_results.plist");
    executionContext = TestExecutionContext.newInstance();
    codeSignIdentitiesSupplier = Suppliers.ofInstance(ImmutableList.of());
  }

  @Test
  public void testFailsWithInvalidEntitlementsPlist() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(startsWith("Malformed entitlement .plist: "));

    BuildStepResultHolder<ProvisioningProfileMetadata> profileMetadataHolder =
        new BuildStepResultHolder<>();
    ProvisioningProfileSelectStep step =
        new ProvisioningProfileSelectStep(
            projectFilesystem,
            testdataDir.resolve("Info.plist"),
            ApplePlatform.IPHONEOS,
            Optional.of(testdataDir.resolve("Invalid.plist")),
            codeSignIdentitiesSupplier,
            ProvisioningProfileStoreFactory.fromSearchPath(
                new DefaultProcessExecutor(new TestConsole()), FAKE_READ_COMMAND, testdataDir),
            Optional.of(dryRunResultFile),
            profileMetadataHolder);

    step.execute(executionContext);
  }

  @Test
  public void testFailsWithInvalidInfoPlist() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    thrown.expect(IOException.class);
    thrown.expectMessage(containsString("not a property list"));

    BuildStepResultHolder<ProvisioningProfileMetadata> profileMetadataHolder =
        new BuildStepResultHolder<>();
    ProvisioningProfileSelectStep step =
        new ProvisioningProfileSelectStep(
            projectFilesystem,
            testdataDir.resolve("Invalid.plist"),
            ApplePlatform.IPHONEOS,
            Optional.empty(),
            codeSignIdentitiesSupplier,
            ProvisioningProfileStoreFactory.fromSearchPath(
                new DefaultProcessExecutor(new TestConsole()), FAKE_READ_COMMAND, testdataDir),
            Optional.of(dryRunResultFile),
            profileMetadataHolder);

    step.execute(executionContext);
  }

  @Test
  public void testFailsWithNoSuitableProfilesFound() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "No valid non-expired provisioning profiles match for *.com.example.TestApp");

    Path emptyDir =
        TestDataHelper.getTestDataDirectory(this).resolve("provisioning_profiles_empty");

    BuildStepResultHolder<ProvisioningProfileMetadata> profileMetadataHolder =
        new BuildStepResultHolder<>();
    ProvisioningProfileSelectStep step =
        new ProvisioningProfileSelectStep(
            projectFilesystem,
            testdataDir.resolve("Info.plist"),
            ApplePlatform.IPHONEOS,
            Optional.empty(),
            codeSignIdentitiesSupplier,
            ProvisioningProfileStoreFactory.fromSearchPath(
                new DefaultProcessExecutor(new TestConsole()), FAKE_READ_COMMAND, emptyDir),
            Optional.empty(),
            profileMetadataHolder);

    step.execute(executionContext);
  }

  @Test
  public void testDoesNotFailInDryRunMode() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    Path emptyDir =
        TestDataHelper.getTestDataDirectory(this).resolve("provisioning_profiles_empty");

    BuildStepResultHolder<ProvisioningProfileMetadata> profileMetadataHolder =
        new BuildStepResultHolder<>();
    ProvisioningProfileSelectStep step =
        new ProvisioningProfileSelectStep(
            projectFilesystem,
            testdataDir.resolve("Info.plist"),
            ApplePlatform.IPHONEOS,
            Optional.empty(),
            codeSignIdentitiesSupplier,
            ProvisioningProfileStoreFactory.fromSearchPath(
                new DefaultProcessExecutor(new TestConsole()), FAKE_READ_COMMAND, emptyDir),
            Optional.of(dryRunResultFile),
            profileMetadataHolder);

    step.execute(executionContext);
    assertNotNull(profileMetadataHolder.getValue());
    assertFalse(profileMetadataHolder.getValue().isPresent());

    Optional<String> resultContents = projectFilesystem.readFileIfItExists(dryRunResultFile);
    assertTrue(resultContents.isPresent());
    NSDictionary resultPlist =
        (NSDictionary)
            PropertyListParser.parse(resultContents.get().getBytes(StandardCharsets.UTF_8));

    assertEquals(new NSString("com.example.TestApp"), resultPlist.get("bundle-id"));
  }

  @Test
  public void shouldSetProvisioningProfileHolderWhenStepIsRun() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    BuildStepResultHolder<ProvisioningProfileMetadata> profileMetadataHolder =
        new BuildStepResultHolder<>();
    ProvisioningProfileSelectStep step =
        new ProvisioningProfileSelectStep(
            projectFilesystem,
            testdataDir.resolve("Info.plist"),
            ApplePlatform.IPHONEOS,
            Optional.empty(),
            codeSignIdentitiesSupplier,
            ProvisioningProfileStoreFactory.fromSearchPath(
                new DefaultProcessExecutor(new TestConsole()), FAKE_READ_COMMAND, testdataDir),
            Optional.empty(),
            profileMetadataHolder);

    step.execute(executionContext);
    assertNotNull(profileMetadataHolder.getValue());
  }
}
