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

package com.facebook.buck.apple.toolchain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.apple.platform_type.ApplePlatformType;
import com.facebook.buck.core.model.InternalFlavor;
import java.util.Optional;
import org.junit.Test;

public class ApplePlatformTest {
  @Test
  public void definitions() {
    assertSame(ApplePlatform.MACOSX.getType(), ApplePlatformType.MAC);
    assertTrue(ApplePlatform.MACOSX.getName().contains("osx"));
    assertFalse(ApplePlatform.MACOSX.getName().contains("iphoneos"));
    assertFalse(ApplePlatform.MACOSX.getName().contains("iphonesimulator"));
    assertFalse(ApplePlatform.MACOSX.getName().contains("watchos"));
    assertFalse(ApplePlatform.MACOSX.getName().contains("watchsimulator"));
    assertFalse(ApplePlatform.MACOSX.getName().contains("appletvos"));
    assertFalse(ApplePlatform.MACOSX.getName().contains("appletvsimulator"));

    assertSame(ApplePlatform.IPHONEOS.getType(), ApplePlatformType.IOS_DEVICE);
    assertFalse(ApplePlatform.IPHONEOS.getName().contains("osx"));
    assertTrue(ApplePlatform.IPHONEOS.getName().contains("iphoneos"));
    assertFalse(ApplePlatform.IPHONEOS.getName().contains("iphonesimulator"));
    assertFalse(ApplePlatform.IPHONEOS.getName().contains("watchos"));
    assertFalse(ApplePlatform.IPHONEOS.getName().contains("watchsimulator"));
    assertFalse(ApplePlatform.IPHONEOS.getName().contains("appletvos"));
    assertFalse(ApplePlatform.IPHONEOS.getName().contains("appletvsimulator"));

    assertSame(ApplePlatform.IPHONESIMULATOR.getType(), ApplePlatformType.IOS_SIMULATOR);
    assertFalse(ApplePlatform.IPHONESIMULATOR.getName().contains("osx"));
    assertFalse(ApplePlatform.IPHONESIMULATOR.getName().contains("iphoneos"));
    assertTrue(ApplePlatform.IPHONESIMULATOR.getName().contains("iphonesimulator"));
    assertFalse(ApplePlatform.IPHONESIMULATOR.getName().contains("watchos"));
    assertFalse(ApplePlatform.IPHONESIMULATOR.getName().contains("watchsimulator"));
    assertFalse(ApplePlatform.IPHONESIMULATOR.getName().contains("appletvos"));
    assertFalse(ApplePlatform.IPHONESIMULATOR.getName().contains("appletvsimulator"));

    assertSame(ApplePlatform.WATCHOS.getType(), ApplePlatformType.WATCH_DEVICE);
    assertFalse(ApplePlatform.WATCHOS.getName().contains("osx"));
    assertFalse(ApplePlatform.WATCHOS.getName().contains("iphoneos"));
    assertFalse(ApplePlatform.WATCHOS.getName().contains("iphonesimulator"));
    assertTrue(ApplePlatform.WATCHOS.getName().contains("watchos"));
    assertFalse(ApplePlatform.WATCHOS.getName().contains("watchsimulator"));
    assertFalse(ApplePlatform.WATCHOS.getName().contains("appletvos"));
    assertFalse(ApplePlatform.WATCHOS.getName().contains("appletvsimulator"));

    assertSame(ApplePlatform.WATCHSIMULATOR.getType(), ApplePlatformType.WATCH_SIMULATOR);
    assertFalse(ApplePlatform.WATCHSIMULATOR.getName().contains("osx"));
    assertFalse(ApplePlatform.WATCHSIMULATOR.getName().contains("iphoneos"));
    assertFalse(ApplePlatform.WATCHSIMULATOR.getName().contains("iphonesimulator"));
    assertFalse(ApplePlatform.WATCHSIMULATOR.getName().contains("watchos"));
    assertTrue(ApplePlatform.WATCHSIMULATOR.getName().contains("watchsimulator"));
    assertFalse(ApplePlatform.WATCHSIMULATOR.getName().contains("appletvos"));
    assertFalse(ApplePlatform.WATCHSIMULATOR.getName().contains("appletvsimulator"));

    assertSame(ApplePlatform.APPLETVOS.getType(), ApplePlatformType.TV_DEVICE);
    assertFalse(ApplePlatform.APPLETVOS.getName().contains("osx"));
    assertFalse(ApplePlatform.APPLETVOS.getName().contains("iphoneos"));
    assertFalse(ApplePlatform.APPLETVOS.getName().contains("iphonesimulator"));
    assertFalse(ApplePlatform.APPLETVOS.getName().contains("watchos"));
    assertFalse(ApplePlatform.APPLETVOS.getName().contains("watchsimulator"));
    assertTrue(ApplePlatform.APPLETVOS.getName().contains("appletvos"));
    assertFalse(ApplePlatform.APPLETVOS.getName().contains("appletvsimulator"));

    assertSame(ApplePlatform.APPLETVSIMULATOR.getType(), ApplePlatformType.TV_SIMULATOR);
    assertFalse(ApplePlatform.APPLETVSIMULATOR.getName().contains("osx"));
    assertFalse(ApplePlatform.APPLETVSIMULATOR.getName().contains("iphoneos"));
    assertFalse(ApplePlatform.APPLETVSIMULATOR.getName().contains("iphonesimulator"));
    assertFalse(ApplePlatform.APPLETVSIMULATOR.getName().contains("watchos"));
    assertFalse(ApplePlatform.APPLETVSIMULATOR.getName().contains("watchsimulator"));
    assertFalse(ApplePlatform.APPLETVSIMULATOR.getName().contains("appletvos"));
    assertTrue(ApplePlatform.APPLETVSIMULATOR.getName().contains("appletvsimulator"));
  }

  @Test
  public void watchTypes() {
    ApplePlatform[] watchPlatforms = {ApplePlatform.WATCHOS, ApplePlatform.WATCHSIMULATOR};

    for (ApplePlatform platform : watchPlatforms) {
      assertTrue(platform.getType().isWatch());
      assertTrue(platform.getName().contains("watch"));
    }
  }

  @Test
  public void testPlatformFlavorsDetection() {
    assertTrue(ApplePlatform.isPlatformFlavor(InternalFlavor.of("iphoneos-armv7")));
    assertFalse(ApplePlatform.isPlatformFlavor(InternalFlavor.of("iphoneos-armv7abc")));
    assertFalse(ApplePlatform.isPlatformFlavor(InternalFlavor.of("abciphoneos-armv7")));
    assertFalse(ApplePlatform.isPlatformFlavor(InternalFlavor.of("iphoneosarmv7")));
    assertTrue(ApplePlatform.isPlatformFlavor(InternalFlavor.of("macosx11.1-x86_64")));
    assertFalse(ApplePlatform.isPlatformFlavor(InternalFlavor.of("iphoneosssss")));
  }

  @Test
  public void testAppleSDKNameExtraction() {
    assertEquals(
        ApplePlatform.findAppleSdkName(InternalFlavor.of("watchos-armv7k")),
        Optional.of("watchos"));
    assertEquals(
        ApplePlatform.findAppleSdkName(InternalFlavor.of("iphoneos-armv7abc")), Optional.empty());
    assertEquals(
        ApplePlatform.findAppleSdkName(InternalFlavor.of("abciphoneos-armv7")), Optional.empty());
    assertEquals(
        ApplePlatform.findAppleSdkName(InternalFlavor.of("iphoneosarmv7")), Optional.empty());
    assertEquals(
        ApplePlatform.findAppleSdkName(InternalFlavor.of("iphonesimulator11.1-x86_64")),
        Optional.of("iphonesimulator11.1"));
  }
}
