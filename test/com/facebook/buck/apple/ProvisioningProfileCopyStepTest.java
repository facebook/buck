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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Date;

public class ProvisioningProfileCopyStepTest {
  private Path testdataDir;
  private Path tempOutputDir;
  private Path outputFile;
  private Path xcentFile;
  private ProjectFilesystem projectFilesystem;
  private ExecutionContext executionContext;

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() throws IOException {
    testdataDir = TestDataHelper.getTestDataDirectory(this).resolve("provisioning_profiles");
    projectFilesystem = new FakeProjectFilesystem(testdataDir.toFile());
    DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();
    tmp.create();
    tempOutputDir = tmp.getRootPath();
    outputFile = tempOutputDir.resolve("embedded.mobileprovision");
    xcentFile = tempOutputDir.resolve("test.xcent");
    executionContext = TestExecutionContext.newInstance();
  }

  private static ProvisioningProfileMetadata makeTestMetadata(
    String appID, Date expirationDate, String uuid) throws Exception {
    return ProvisioningProfileMetadata.builder()
        .setAppID(ProvisioningProfileMetadata.splitAppID(appID))
        .setExpirationDate(expirationDate)
        .setUUID(uuid)
        .build();
  };

  @Test
  public void testExpiredProfilesAreIgnored() throws Exception {
    ImmutableSet<ProvisioningProfileMetadata> profiles =
        ImmutableSet.<ProvisioningProfileMetadata>of(
            makeTestMetadata("AAAAAAAAAA.*", new Date(0), "00000000-0000-0000-0000-000000000000")
        );

    Optional<ProvisioningProfileMetadata> actual =
        ProvisioningProfileCopyStep.getBestProvisioningProfile(
            profiles, "com.facebook.test", Optional.<String>absent(), Optional.<String>absent());

    assertThat(actual, is(equalTo(Optional.<ProvisioningProfileMetadata>absent())));
  }

  @Test
  public void testPrefixOverride() throws Exception {
    ProvisioningProfileMetadata expected =
        makeTestMetadata("AAAAAAAAAA.*", new Date(Long.MAX_VALUE),
            "00000000-0000-0000-0000-000000000000");

    ImmutableSet<ProvisioningProfileMetadata> profiles =
        ImmutableSet.<ProvisioningProfileMetadata>of(
            expected,
            makeTestMetadata("BBBBBBBBBB.com.facebook.test", new Date(Long.MAX_VALUE),
                "00000000-0000-0000-0000-000000000000")
        );

    Optional<ProvisioningProfileMetadata> actual =
        ProvisioningProfileCopyStep.getBestProvisioningProfile(
            profiles, "com.facebook.test", Optional.<String>absent(),
            Optional.<String>of("AAAAAAAAAA"));

    assertThat(actual.get(), is(equalTo(expected)));
  }

  @Test
  public void testUUIDOverride() throws Exception {
    ProvisioningProfileMetadata expected =
        makeTestMetadata("BBBBBBBBBB.*", new Date(Long.MAX_VALUE),
            "11111111-1111-1111-1111-111111111111");

    ImmutableSet<ProvisioningProfileMetadata> profiles =
        ImmutableSet.<ProvisioningProfileMetadata>of(
            expected,
            makeTestMetadata("BBBBBBBBBB.com.facebook.test", new Date(Long.MAX_VALUE),
                "00000000-0000-0000-0000-000000000000")
        );

    Optional<ProvisioningProfileMetadata> actual =
        ProvisioningProfileCopyStep.getBestProvisioningProfile(
            profiles, "com.facebook.test",
            Optional.<String>of("11111111-1111-1111-1111-111111111111"),
            Optional.<String>absent());

    assertThat(actual.get(), is(equalTo(expected)));
  }

  @Test
  public void testMatchesSpecificApp() throws Exception {
    ProvisioningProfileMetadata expected =
        makeTestMetadata("BBBBBBBBBB.com.facebook.test", new Date(Long.MAX_VALUE),
            "00000000-0000-0000-0000-000000000000");

    ImmutableSet<ProvisioningProfileMetadata> profiles =
        ImmutableSet.<ProvisioningProfileMetadata>of(
            expected,
            makeTestMetadata("BBBBBBBBBB.com.facebook.*", new Date(Long.MAX_VALUE),
                "11111111-1111-1111-1111-111111111111")
        );

    Optional<ProvisioningProfileMetadata> actual =
        ProvisioningProfileCopyStep.getBestProvisioningProfile(
            profiles, "com.facebook.test",
            Optional.<String>absent(),
            Optional.<String>absent());

    assertThat(actual.get(), is(equalTo(expected)));
  }

  @Test
  public void testMatchesWildcard() throws Exception {
    ProvisioningProfileMetadata expected =
        makeTestMetadata("BBBBBBBBBB.*", new Date(Long.MAX_VALUE),
            "00000000-0000-0000-0000-000000000000");

    ImmutableSet<ProvisioningProfileMetadata> profiles =
        ImmutableSet.<ProvisioningProfileMetadata>of(
            expected
        );

    Optional<ProvisioningProfileMetadata> actual =
        ProvisioningProfileCopyStep.getBestProvisioningProfile(
            profiles, "com.facebook.test",
            Optional.<String>absent(),
            Optional.<String>absent());

    assertThat(actual.get(), is(equalTo(expected)));
  }

  @Test
  public void testFailsWithInvalidEntitlementsPlist() throws Exception {
    thrown.expect(HumanReadableException.class);

    ProvisioningProfileCopyStep step = new ProvisioningProfileCopyStep(
        projectFilesystem,
        testdataDir.resolve("Info.plist"),
        Optional.<String>absent(),
        Optional.<Path>of(testdataDir.resolve("Invalid.plist")),
        ProvisioningProfileCopyStep.findProfilesInPath(testdataDir),
        outputFile,
        xcentFile
    );

    step.execute(executionContext);
  }

  @Test
  public void testFailsWithInvalidInfoPlist() throws Exception {
    thrown.expect(HumanReadableException.class);

    ProvisioningProfileCopyStep step = new ProvisioningProfileCopyStep(
        projectFilesystem,
        testdataDir.resolve("Invalid.plist"),
        Optional.<String>absent(),
        Optional.<Path>absent(),
        ProvisioningProfileCopyStep.findProfilesInPath(testdataDir),
        outputFile,
        xcentFile
    );

    step.execute(executionContext);
  }
}
