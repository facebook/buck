/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.android;

import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.easymock.EasyMockSupport;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ProGuardObfuscateStepTest extends EasyMockSupport {
  @Rule
  public final TemporaryFolder tmpDir = new TemporaryFolder();

  @Test
  public void testCreateEmptyZip() throws Exception {
    File tmpFile = tmpDir.newFile();
    ProGuardObfuscateStep.createEmptyZip(tmpFile);

    // Try to read it.
    try (ZipFile zipFile = new ZipFile(tmpFile)) {
      int totalSize = 0;
      List<? extends ZipEntry> entries = Collections.list(zipFile.entries());

      assertTrue("Expected either 0 or 1 entry", entries.size() <= 1);
      for (ZipEntry entry : entries) {
        totalSize += entry.getSize();
      }
      assertEquals("Zip file should have zero-length contents", 0, totalSize);
    }
  }

  @Test
  public void testSdkConfigArgs() {
    ExecutionContext context = createMock(ExecutionContext.class);
    AndroidPlatformTarget target = createMock(AndroidPlatformTarget.class);
    expect(context.getProjectDirectoryRoot()).andStubReturn(Paths.get("root"));
    expect(context.getAndroidPlatformTarget()).andStubReturn(target);
    expect(target.getProguardConfig()).andStubReturn(Paths.get("sdk-default.pro"));
    expect(target.getOptimizedProguardConfig()).andStubReturn(Paths.get("sdk-optimized.pro"));
    expect(target.getBootclasspathEntries()).andStubReturn(ImmutableList.<Path>of());
    replayAll();

    checkSdkConfig(context, ProGuardObfuscateStep.SdkProguardType.DEFAULT, "sdk-default.pro");
    checkSdkConfig(context, ProGuardObfuscateStep.SdkProguardType.OPTIMIZED, "sdk-optimized.pro");
    checkSdkConfig(context, ProGuardObfuscateStep.SdkProguardType.NONE, null);

    verifyAll();
  }

  @Test
  public void testAdditionalLibraryJarsParameterFormatting() {
    ExecutionContext context = createMock(ExecutionContext.class);
    AndroidPlatformTarget target = createMock(AndroidPlatformTarget.class);
    expect(context.getProjectDirectoryRoot()).andStubReturn(Paths.get("root"));
    expect(context.getAndroidPlatformTarget()).andStubReturn(target);
    expect(target.getProguardConfig()).andStubReturn(Paths.get("sdk-default.pro"));
    expect(target.getOptimizedProguardConfig()).andStubReturn(Paths.get("sdk-optimized.pro"));
    expect(target.getBootclasspathEntries()).andStubReturn(ImmutableList.<Path>of());
    replayAll();

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    ProGuardObfuscateStep.create(
        /* proguardJarOverride */ Optional.<Path>absent(),
        "1024M",
        Paths.get("generated/proguard.txt"),
        /* customProguardConfigs */ ImmutableSet.<Path>of(),
        ProGuardObfuscateStep.SdkProguardType.DEFAULT,
        /* optimizationPasses */ Optional.<Integer>absent(),
        /* inputAndOutputEntries */ ImmutableMap.<Path, Path>of(),
        /* additionalLibraryJarsForProguard */ ImmutableSet.of(
            Paths.get("myfavorite.jar"), Paths.get("another.jar")),
        Paths.get("proguard-directory"),
        new FakeBuildableContext(),
        steps);
    ProGuardObfuscateStep.CommandLineHelperStep commandLineHelperStep =
        (ProGuardObfuscateStep.CommandLineHelperStep) steps.build().get(0);
    ImmutableList<String> parameters = commandLineHelperStep.getParameters(context);
    int libraryJarsArgIndex = parameters.indexOf("-libraryjars");
    int libraryJarsValueIndex = parameters.indexOf("myfavorite.jar:another.jar");
    assertNotEquals(-1, libraryJarsArgIndex);
    assertEquals(libraryJarsValueIndex, libraryJarsArgIndex + 1);
  }

  private void checkSdkConfig(
      ExecutionContext context,
      ProGuardObfuscateStep.SdkProguardType sdkProguardConfig,
      String expectedPath) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    ProGuardObfuscateStep.create(
        /* proguardJarOverride */ Optional.<Path>absent(),
        "1024M",
        Paths.get("generated/proguard.txt"),
        /* customProguardConfigs */ ImmutableSet.<Path>of(),
        sdkProguardConfig,
        /* optimizationPasses */ Optional.<Integer>absent(),
        /* inputAndOutputEntries */ ImmutableMap.<Path, Path>of(),
        /* additionalLibraryJarsForProguard */ ImmutableSet.<Path>of(),
        Paths.get("proguard-directory"),
        new FakeBuildableContext(),
        steps);
    ProGuardObfuscateStep.CommandLineHelperStep commandLineHelperStep =
        (ProGuardObfuscateStep.CommandLineHelperStep) steps.build().get(0);

    String found = null;
    Iterator<String> argsIt = commandLineHelperStep.getParameters(context).iterator();
    while (argsIt.hasNext()) {
      String arg = argsIt.next();
      if (!arg.equals("-include")) {
        continue;
      }
      assertTrue(argsIt.hasNext());
      String file = argsIt.next();
      if (file.startsWith("sdk-")) {
        found = file;
        break;
      }
    }

    assertEquals(expectedPath, found);
  }
}
