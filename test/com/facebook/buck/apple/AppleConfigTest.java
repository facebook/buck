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
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.hamcrest.junit.ExpectedException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AppleConfigTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
  }

  @Test
  public void getUnspecifiedAppleDeveloperDirectorySupplier() {
    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    AppleConfig config = buckConfig.getView(AppleConfig.class);
    assertNotNull(config.getAppleDeveloperDirectorySupplier(new FakeProcessExecutor()));
  }

  @Test
  public void getExtraAppleDeveloperDirectories() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "apple",
                    ImmutableMap.of(
                        "extra_toolchain_paths", "/path/to/somewhere/Toolchain",
                        "extra_platform_paths", "/path/to/somewhere/Platform")))
            .build();
    AppleConfig config = buckConfig.getView(AppleConfig.class);
    ImmutableList<Path> extraToolchainPaths = config.getExtraToolchainPaths();
    ImmutableList<Path> extraPlatformPaths = config.getExtraPlatformPaths();
    assertEquals(ImmutableList.of(Paths.get("/path/to/somewhere/Toolchain")), extraToolchainPaths);
    assertEquals(ImmutableList.of(Paths.get("/path/to/somewhere/Platform")), extraPlatformPaths);
  }

  @Test
  public void getXcodeSelectDetectedAppleDeveloperDirectorySupplier() {
    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    AppleConfig config = buckConfig.getView(AppleConfig.class);
    ProcessExecutorParams xcodeSelectParams =
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("xcode-select", "--print-path"))
            .build();
    FakeProcess fakeXcodeSelect = new FakeProcess(0, "/path/to/another/place", "");
    FakeProcessExecutor processExecutor =
        new FakeProcessExecutor(ImmutableMap.of(xcodeSelectParams, fakeXcodeSelect));
    Supplier<Optional<Path>> supplier = config.getAppleDeveloperDirectorySupplier(processExecutor);
    assertNotNull(supplier);
    assertEquals(Optional.of(Paths.get("/path/to/another/place")), supplier.get());
  }

  @Test
  public void packageConfigCommandWithoutExtensionShouldThrow() {
    AppleConfig config =
        FakeBuckConfig.builder()
            .setSections("[apple]", "iphoneos_package_command = echo")
            .build()
            .getView(AppleConfig.class);
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(containsString("be both specified, or be both omitted"));
    config.getPackageConfigForPlatform(ApplePlatform.IPHONEOS);
  }

  @Test
  public void packageConfigExtensionWithoutCommandShouldThrow() {
    AppleConfig config =
        FakeBuckConfig.builder()
            .setSections("[apple]", "iphoneos_package_extension = api")
            .build()
            .getView(AppleConfig.class);
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(containsString("be both specified, or be both omitted"));
    config.getPackageConfigForPlatform(ApplePlatform.IPHONEOS);
  }

  @Test
  public void packageConfigTreatsEmptyStringAsOmitted() {
    AppleConfig config =
        FakeBuckConfig.builder()
            .setSections("[apple]", "iphoneos_package_extension = ", "iphoneos_package_command = ")
            .build()
            .getView(AppleConfig.class);
    assertThat(
        config.getPackageConfigForPlatform(ApplePlatform.IPHONEOS), equalTo(Optional.empty()));
  }

  @Test
  public void packageConfigExtractsValuesFromConfig() {
    AppleConfig config =
        FakeBuckConfig.builder()
            .setSections(
                "[apple]",
                "iphoneos_package_extension = api",
                "iphoneos_package_command = echo $OUT")
            .build()
            .getView(AppleConfig.class);
    Optional<ApplePackageConfig> packageConfig =
        config.getPackageConfigForPlatform(ApplePlatform.IPHONEOS);
    assertThat(packageConfig.get().getCommand(), equalTo("echo $OUT"));
    assertThat(packageConfig.get().getExtension(), equalTo("api"));
  }

  @Test
  public void getOverridenCompressionLevel() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("apple", ImmutableMap.of("ipa_compression_level", "Max")))
            .build();
    AppleConfig config = buckConfig.getView(AppleConfig.class);
    ZipCompressionLevel compressionLevel = config.getZipCompressionLevel();
    assertEquals(ZipCompressionLevel.MAX, compressionLevel);
  }

  @Test
  public void getDefaultCompressionLevel() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("apple", ImmutableMap.of("ipa_compression_level", "")))
            .build();
    AppleConfig config = buckConfig.getView(AppleConfig.class);
    ZipCompressionLevel compressionLevel = config.getZipCompressionLevel();
    assertEquals(ZipCompressionLevel.DEFAULT, compressionLevel);
  }

  @Test
  public void testNegativeCodesignTimout() {
    /* negative values should throw */
    AppleConfig config =
        FakeBuckConfig.builder()
            .setSections("[apple]", "codesign_timeout = -1")
            .build()
            .getView(AppleConfig.class);
    HumanReadableException exception = null;
    try {
      config.getCodesignTimeout();
    } catch (HumanReadableException e) {
      exception = e;
    }
    assertThat(exception, notNullValue());
    assertThat(
        "Should throw exceptions for negative timeouts.",
        exception.getHumanReadableErrorMessage(),
        startsWith("negative timeout"));
  }

  @Test
  public void testDefaultCodesignTimeout() {
    /* make sure that we have a sane default of 300s when the value is not specified */
    AppleConfig config = FakeBuckConfig.builder().build().getView(AppleConfig.class);
    assertThat(config.getCodesignTimeout(), equalTo(Duration.ofSeconds(300)));
  }
}
