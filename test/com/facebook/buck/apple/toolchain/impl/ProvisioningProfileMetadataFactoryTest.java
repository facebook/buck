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

package com.facebook.buck.apple.toolchain.impl;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.dd.plist.NSDate;
import com.facebook.buck.apple.toolchain.ProvisioningProfileMetadata;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ProvisioningProfileMetadataFactoryTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  private static final ImmutableList<String> FAKE_READ_COMMAND = ImmutableList.of("cat");

  @Test
  public void testParseProvisioningProfileFile() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProcessExecutor executor = new DefaultProcessExecutor(new TestConsole());
    Path testdataDir = TestDataHelper.getTestDataDirectory(this).resolve("provisioning_profiles");
    Path testFile = testdataDir.resolve("sample.mobileprovision");

    ProvisioningProfileMetadata data =
        ProvisioningProfileMetadataFactory.fromProvisioningProfilePath(
            executor, FAKE_READ_COMMAND, testFile);

    assertThat(data.getExpirationDate(), is(equalTo(new NSDate("9999-03-05T01:33:40Z").getDate())));
    assertThat(data.getAppID(), is(equalTo(new Pair<>("ABCDE12345", "com.example.TestApp"))));
    assertThat(data.getUUID(), is(equalTo("00000000-0000-0000-0000-000000000000")));
    assertThat(data.getProfilePath(), is(equalTo(testFile)));
    assertThat(
        data.getDeveloperCertificateFingerprints(),
        equalTo(ImmutableSet.of(HashCode.fromString("be16fc419bfb6b59a86bc08755ba0f332ec574fb"))));

    // Test old-style provisioning profile without "Platforms" field
    data =
        ProvisioningProfileMetadataFactory.fromProvisioningProfilePath(
            executor,
            FAKE_READ_COMMAND,
            testdataDir.resolve("sample_without_platforms.mobileprovision"));
    assertThat(
        data.getDeveloperCertificateFingerprints(),
        equalTo(ImmutableSet.of(HashCode.fromString("be16fc419bfb6b59a86bc08755ba0f332ec574fb"))));

    thrown.expect(IllegalArgumentException.class);
    ProvisioningProfileMetadataFactory.fromProvisioningProfilePath(
        executor, FAKE_READ_COMMAND, testdataDir.resolve("invalid.mobileprovision"));
  }

  @Test
  public void testProvisioningProfileReadCommandOverride() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProcessExecutor executor = new DefaultProcessExecutor(new TestConsole());
    Path testdataDir = TestDataHelper.getTestDataDirectory(this).resolve("provisioning_profiles");

    ProvisioningProfileMetadata data =
        ProvisioningProfileMetadataFactory.fromProvisioningProfilePath(
            executor,
            ImmutableList.of(testdataDir.resolve("fake_read_command.sh").toString()),
            Paths.get("unused"));
    assertThat(data.getAppID(), is(equalTo(new Pair<>("0000000000", "com.example.override"))));
  }

  @Test
  public void testFilteredEntitlementsStripOut() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProcessExecutor executor = new DefaultProcessExecutor(new TestConsole());
    Path testdataDir = TestDataHelper.getTestDataDirectory(this).resolve("provisioning_profiles");
    Path testFile = testdataDir.resolve("sample.mobileprovision");

    ProvisioningProfileMetadata data =
        ProvisioningProfileMetadataFactory.fromProvisioningProfilePath(
            executor, FAKE_READ_COMMAND, testFile);

    assertTrue(
        data.getEntitlements()
            .containsKey("com.apple.developer.icloud-container-development-container-identifiers"));
    assertFalse(
        data.getMergeableEntitlements()
            .containsKey("com.apple.developer.icloud-container-development-container-identifiers"));
  }
}
