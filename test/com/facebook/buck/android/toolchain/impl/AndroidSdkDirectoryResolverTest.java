/*
 * Copyright 2013-present Facebook, Inc.
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
package com.facebook.buck.android.toolchain.impl;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.FakeAndroidBuckConfig;
import com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkHelper;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AndroidSdkDirectoryResolverTest {

  @Rule public TemporaryPaths tmpDir = new TemporaryPaths();
  @Rule public TemporaryPaths tmpDir2 = new TemporaryPaths();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void throwAtAbsentSdk() {
    AndroidSdkDirectoryResolver resolver =
        new AndroidSdkDirectoryResolver(
            tmpDir.getRoot().getFileSystem(), ImmutableMap.of(), AndroidNdkHelper.DEFAULT_CONFIG);

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(AndroidSdkDirectoryResolver.SDK_NOT_FOUND_MESSAGE);
    resolver.getSdkOrThrow();
  }

  @Test
  public void throwAtSdkPathIsNotDirectory() throws IOException {
    Path file = tmpDir.getRoot().resolve(tmpDir.newFile("file"));
    AndroidSdkDirectoryResolver resolver =
        new AndroidSdkDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_SDK", file.toString()),
            AndroidNdkHelper.DEFAULT_CONFIG);

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        String.format(
            AndroidSdkDirectoryResolver.INVALID_DIRECTORY_MESSAGE_TEMPLATE, "ANDROID_SDK", file));
    resolver.getSdkOrThrow();
  }

  @Test
  public void testGetSdkFromBuckconfig() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    AndroidSdkDirectoryResolver resolver =
        new AndroidSdkDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of(),
            FakeAndroidBuckConfig.builder().setSdkPath(sdkDir.toString()).build());

    assertEquals(sdkDir, resolver.getSdkOrThrow());
  }

  @Test
  public void testSdkFromEnvironmentSupercedesBuckconfig() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    AndroidSdkDirectoryResolver resolver =
        new AndroidSdkDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_SDK", sdkDir.resolve("also-wrong").toString()),
            FakeAndroidBuckConfig.builder().setSdkPath(sdkDir.toString()).build());
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        String.format(
            AndroidSdkDirectoryResolver.INVALID_DIRECTORY_MESSAGE_TEMPLATE,
            "ANDROID_SDK",
            sdkDir.resolve("also-wrong")));
    resolver.getSdkOrThrow();
  }

  @Test
  public void testBuckConfigEntryInSdkPathSearchOrderIsUsed() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    Path envDir = tmpDir2.newFolder("sdk");
    AndroidSdkDirectoryResolver resolver =
        new AndroidSdkDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_SDK", envDir.toString()),
            FakeAndroidBuckConfig.builder()
                .setSdkPath(sdkDir.toString())
                .setSdkPathSearchOrder("<CONFIG>, ANDROID_SDK")
                .build());
    assertEquals(sdkDir, resolver.getSdkOrThrow());
  }

  @Test
  public void testEnvVariableInSdkPathSearchOrderIsUsed() throws IOException {
    Path envDir = tmpDir.newFolder("sdk");
    AndroidSdkDirectoryResolver resolver =
        new AndroidSdkDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_SDK", envDir.toString()),
            FakeAndroidBuckConfig.builder().setSdkPathSearchOrder("<CONFIG>, ANDROID_SDK").build());
    assertEquals(envDir, resolver.getSdkOrThrow());
  }

  @Test
  public void testFirstEnvVariableInSdkPathSearchOrderIsUsed() throws IOException {
    Path env1Dir = tmpDir.newFolder("sdk");
    Path env2Dir = tmpDir2.newFolder("sdk");
    AndroidSdkDirectoryResolver resolver =
        new AndroidSdkDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_SDK", env1Dir.toString(), "ANDROID_HOME", env2Dir.toString()),
            FakeAndroidBuckConfig.builder()
                .setSdkPathSearchOrder("ANDROID_SDK, ANDROID_HOME")
                .build());
    assertEquals(env1Dir, resolver.getSdkOrThrow());
  }
}
