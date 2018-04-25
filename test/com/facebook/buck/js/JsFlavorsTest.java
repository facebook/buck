/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.js;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.FlavorDomainException;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import org.junit.BeforeClass;
import org.junit.Test;

public class JsFlavorsTest {

  private static JsBundleDescription bundleDescription;
  private static JsLibraryDescription libraryDescription;

  @BeforeClass
  public static void setupDescriptions() {
    bundleDescription = new JsBundleDescription(new ToolchainProviderBuilder().build());
    libraryDescription = new JsLibraryDescription();
  }

  @Test
  public void bundleArgsHaveAndroidFlag() {
    assertEquals(
        "--platform android", JsFlavors.bundleJobArgs(ImmutableSortedSet.of(JsFlavors.ANDROID)));
  }

  @Test
  public void bundleArgsHaveIOSFlag() {
    assertEquals("--platform ios", JsFlavors.bundleJobArgs(ImmutableSortedSet.of(JsFlavors.IOS)));
  }

  @Test
  public void bundleArgsHaveReleaseWithReleaseFlavor() {
    assertEquals(
        "--platform android --release",
        JsFlavors.bundleJobArgs(ImmutableSortedSet.of(JsFlavors.ANDROID, JsFlavors.RELEASE)));
  }

  @Test
  public void bundleArgsWithFilesRamBundleFlavor() {
    assertEquals(
        "--files-rambundle",
        JsFlavors.bundleJobArgs(ImmutableSortedSet.of(JsFlavors.RAM_BUNDLE_FILES)));
  }

  @Test
  public void bundleArgsWithIndexRamBundleFlavor() {
    assertEquals(
        "--indexed-rambundle",
        JsFlavors.bundleJobArgs(ImmutableSortedSet.of(JsFlavors.RAM_BUNDLE_INDEXED)));
  }

  @Test
  public void bundleArgsIndexedRamBundleFlavorTakesPrecedence() {
    assertEquals(
        "--indexed-rambundle",
        JsFlavors.bundleJobArgs(
            ImmutableSortedSet.of(JsFlavors.RAM_BUNDLE_INDEXED, JsFlavors.RAM_BUNDLE_INDEXED)));
  }

  @Test
  public void bundleArgsUnbundleAndRelease() {
    assertEquals(
        "--indexed-rambundle --release",
        JsFlavors.bundleJobArgs(
            ImmutableSortedSet.of(JsFlavors.RAM_BUNDLE_INDEXED, JsFlavors.RELEASE)));
  }

  @Test
  public void platformArgHasAndroidFlag() {
    assertEquals(
        "--platform android",
        JsFlavors.platformArgForRelease(ImmutableSortedSet.of(JsFlavors.ANDROID)));
  }

  @Test
  public void platformArgHasIOSFlag() {
    assertEquals(
        "--platform ios", JsFlavors.platformArgForRelease(ImmutableSortedSet.of(JsFlavors.IOS)));
  }

  @Test(expected = FlavorDomainException.class)
  public void platformArgFailsForMultiplePlatforms() {
    JsFlavors.platformArgForRelease(ImmutableSortedSet.of(JsFlavors.ANDROID, JsFlavors.IOS));
  }

  @Test(expected = HumanReadableException.class)
  public void platformArgFailsForNoPlatform() {
    JsFlavors.platformArgForRelease(ImmutableSortedSet.of());
  }

  @Test
  public void testBundleFlavors() {
    assertTrue(bundleDescription.hasFlavors(ImmutableSet.of(JsFlavors.RAM_BUNDLE_FILES)));
    assertTrue(bundleDescription.hasFlavors(ImmutableSet.of(JsFlavors.RAM_BUNDLE_INDEXED)));
    assertFalse(libraryDescription.hasFlavors(ImmutableSet.of(JsFlavors.RAM_BUNDLE_FILES)));
    assertFalse(libraryDescription.hasFlavors(ImmutableSet.of(JsFlavors.RAM_BUNDLE_INDEXED)));
  }

  @Test
  public void testBundleFlavorsWithOtherFlavors() {
    assertTrue(
        bundleDescription.hasFlavors(
            ImmutableSet.of(JsFlavors.RAM_BUNDLE_FILES, JsFlavors.RELEASE)));
    assertTrue(
        bundleDescription.hasFlavors(ImmutableSet.of(JsFlavors.RAM_BUNDLE_INDEXED, JsFlavors.IOS)));
  }
}
