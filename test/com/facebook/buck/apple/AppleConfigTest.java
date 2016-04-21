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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.hamcrest.junit.ExpectedException;
import org.junit.Rule;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class AppleConfigTest {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void getUnspecifiedAppleDeveloperDirectorySupplier() {
    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    AppleConfig config = new AppleConfig(buckConfig);
    assertNotNull(config.getAppleDeveloperDirectorySupplier(new FakeProcessExecutor()));
  }

  @Test
  public void getSpecifiedAppleDeveloperDirectorySupplier() {
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(
        ImmutableMap.of(
            "apple",
            ImmutableMap.of("xcode_developer_dir", "/path/to/somewhere"))).build();
    AppleConfig config = new AppleConfig(buckConfig);
    Supplier<Optional<Path>> supplier =
        config.getAppleDeveloperDirectorySupplier(new FakeProcessExecutor());
    assertNotNull(supplier);
    assertEquals(Optional.of(Paths.get("/path/to/somewhere")), supplier.get());

    // Developer directory for tests should fall back to developer dir if not separately specified.
    Supplier<Optional<Path>> supplierForTests =
        config.getAppleDeveloperDirectorySupplierForTests(new FakeProcessExecutor());
    assertNotNull(supplierForTests);
    assertEquals(Optional.of(Paths.get("/path/to/somewhere")), supplierForTests.get());
  }

  @Test
  public void getSpecifiedAppleDeveloperDirectorySupplierForTests() {
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(
        ImmutableMap.of(
            "apple",
            ImmutableMap.of(
                "xcode_developer_dir", "/path/to/somewhere",
                "xcode_developer_dir_for_tests", "/path/to/somewhere2"))).build();
    AppleConfig config = new AppleConfig(buckConfig);
    Supplier<Optional<Path>> supplier =
        config.getAppleDeveloperDirectorySupplier(new FakeProcessExecutor());
    assertNotNull(supplier);
    assertEquals(Optional.of(Paths.get("/path/to/somewhere")), supplier.get());

    Supplier<Optional<Path>> supplierForTests =
        config.getAppleDeveloperDirectorySupplierForTests(new FakeProcessExecutor());
    assertNotNull(supplierForTests);
    assertEquals(Optional.of(Paths.get("/path/to/somewhere2")), supplierForTests.get());
  }

  @Test
  public void getShouldAttemptToDetectBestPlatform() {
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(
        ImmutableMap.of(
            "apple",
            ImmutableMap.of("attempt_to_detect_best_platform", "true"))).build();
    AppleConfig config = new AppleConfig(buckConfig);
    assertThat(config.shouldAttemptToDetermineBestCxxPlatform(), equalTo(true));
  }

  @Test
  public void getXcodeSelectDetectedAppleDeveloperDirectorySupplier() {
    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    AppleConfig config = new AppleConfig(buckConfig);
    ProcessExecutorParams xcodeSelectParams =
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("xcode-select", "--print-path"))
            .build();
    FakeProcess fakeXcodeSelect = new FakeProcess(0, "/path/to/another/place", "");
    FakeProcessExecutor processExecutor = new FakeProcessExecutor(
        ImmutableMap.of(xcodeSelectParams, fakeXcodeSelect));
    Supplier<Optional<Path>> supplier = config.getAppleDeveloperDirectorySupplier(processExecutor);
    assertNotNull(supplier);
    assertEquals(Optional.of(Paths.get("/path/to/another/place")), supplier.get());
  }

  @Test
  public void packageConfigCommandWithoutExtensionShouldThrow() {
    AppleConfig config = new AppleConfig(
        FakeBuckConfig.builder()
            .setSections(
                "[apple]",
                "iphoneos_package_command = echo")
            .build());
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(containsString("be both specified, or be both omitted"));
    config.getPackageConfigForPlatform(ApplePlatform.IPHONEOS);
  }

  @Test
  public void packageConfigExtensionWithoutCommandShouldThrow() {
    AppleConfig config = new AppleConfig(
        FakeBuckConfig.builder()
            .setSections(
                "[apple]",
                "iphoneos_package_extension = api")
            .build());
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(containsString("be both specified, or be both omitted"));
    config.getPackageConfigForPlatform(ApplePlatform.IPHONEOS);
  }

  @Test
  public void packageConfigTreatsEmptyStringAsOmitted() {
    AppleConfig config = new AppleConfig(
        FakeBuckConfig.builder()
            .setSections(
                "[apple]",
                "iphoneos_package_extension = ",
                "iphoneos_package_command = ")
            .build());
    assertThat(
        config.getPackageConfigForPlatform(ApplePlatform.IPHONEOS),
        equalTo(Optional.<ApplePackageConfig>absent()));
  }

  @Test
  public void packageConfigExtractsValuesFromConfig() {
    AppleConfig config = new AppleConfig(
        FakeBuckConfig.builder()
            .setSections(
                "[apple]",
                "iphoneos_package_extension = api",
                "iphoneos_package_command = echo $OUT")
            .build());
    Optional<ApplePackageConfig> packageConfig =
        config.getPackageConfigForPlatform(ApplePlatform.IPHONEOS);
    assertThat(packageConfig.get().getCommand(), equalTo("echo $OUT"));
    assertThat(packageConfig.get().getExtension(), equalTo("api"));
  }
}
