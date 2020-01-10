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
    assertSame(AbstractApplePlatform.MACOSX.getType(), ApplePlatformType.MAC);
    assertTrue(AbstractApplePlatform.MACOSX.getName().contains("osx"));
    assertFalse(AbstractApplePlatform.MACOSX.getName().contains("iphoneos"));
    assertFalse(AbstractApplePlatform.MACOSX.getName().contains("iphonesimulator"));
    assertFalse(AbstractApplePlatform.MACOSX.getName().contains("watchos"));
    assertFalse(AbstractApplePlatform.MACOSX.getName().contains("watchsimulator"));
    assertFalse(AbstractApplePlatform.MACOSX.getName().contains("appletvos"));
    assertFalse(AbstractApplePlatform.MACOSX.getName().contains("appletvsimulator"));

    assertSame(AbstractApplePlatform.IPHONEOS.getType(), ApplePlatformType.IOS_DEVICE);
    assertFalse(AbstractApplePlatform.IPHONEOS.getName().contains("osx"));
    assertTrue(AbstractApplePlatform.IPHONEOS.getName().contains("iphoneos"));
    assertFalse(AbstractApplePlatform.IPHONEOS.getName().contains("iphonesimulator"));
    assertFalse(AbstractApplePlatform.IPHONEOS.getName().contains("watchos"));
    assertFalse(AbstractApplePlatform.IPHONEOS.getName().contains("watchsimulator"));
    assertFalse(AbstractApplePlatform.IPHONEOS.getName().contains("appletvos"));
    assertFalse(AbstractApplePlatform.IPHONEOS.getName().contains("appletvsimulator"));

    assertSame(AbstractApplePlatform.IPHONESIMULATOR.getType(), ApplePlatformType.IOS_SIMULATOR);
    assertFalse(AbstractApplePlatform.IPHONESIMULATOR.getName().contains("osx"));
    assertFalse(AbstractApplePlatform.IPHONESIMULATOR.getName().contains("iphoneos"));
    assertTrue(AbstractApplePlatform.IPHONESIMULATOR.getName().contains("iphonesimulator"));
    assertFalse(AbstractApplePlatform.IPHONESIMULATOR.getName().contains("watchos"));
    assertFalse(AbstractApplePlatform.IPHONESIMULATOR.getName().contains("watchsimulator"));
    assertFalse(AbstractApplePlatform.IPHONESIMULATOR.getName().contains("appletvos"));
    assertFalse(AbstractApplePlatform.IPHONESIMULATOR.getName().contains("appletvsimulator"));

    assertSame(AbstractApplePlatform.WATCHOS.getType(), ApplePlatformType.WATCH_DEVICE);
    assertFalse(AbstractApplePlatform.WATCHOS.getName().contains("osx"));
    assertFalse(AbstractApplePlatform.WATCHOS.getName().contains("iphoneos"));
    assertFalse(AbstractApplePlatform.WATCHOS.getName().contains("iphonesimulator"));
    assertTrue(AbstractApplePlatform.WATCHOS.getName().contains("watchos"));
    assertFalse(AbstractApplePlatform.WATCHOS.getName().contains("watchsimulator"));
    assertFalse(AbstractApplePlatform.WATCHOS.getName().contains("appletvos"));
    assertFalse(AbstractApplePlatform.WATCHOS.getName().contains("appletvsimulator"));

    assertSame(AbstractApplePlatform.WATCHSIMULATOR.getType(), ApplePlatformType.WATCH_SIMULATOR);
    assertFalse(AbstractApplePlatform.WATCHSIMULATOR.getName().contains("osx"));
    assertFalse(AbstractApplePlatform.WATCHSIMULATOR.getName().contains("iphoneos"));
    assertFalse(AbstractApplePlatform.WATCHSIMULATOR.getName().contains("iphonesimulator"));
    assertFalse(AbstractApplePlatform.WATCHSIMULATOR.getName().contains("watchos"));
    assertTrue(AbstractApplePlatform.WATCHSIMULATOR.getName().contains("watchsimulator"));
    assertFalse(AbstractApplePlatform.WATCHSIMULATOR.getName().contains("appletvos"));
    assertFalse(AbstractApplePlatform.WATCHSIMULATOR.getName().contains("appletvsimulator"));

    assertSame(AbstractApplePlatform.APPLETVOS.getType(), ApplePlatformType.TV_DEVICE);
    assertFalse(AbstractApplePlatform.APPLETVOS.getName().contains("osx"));
    assertFalse(AbstractApplePlatform.APPLETVOS.getName().contains("iphoneos"));
    assertFalse(AbstractApplePlatform.APPLETVOS.getName().contains("iphonesimulator"));
    assertFalse(AbstractApplePlatform.APPLETVOS.getName().contains("watchos"));
    assertFalse(AbstractApplePlatform.APPLETVOS.getName().contains("watchsimulator"));
    assertTrue(AbstractApplePlatform.APPLETVOS.getName().contains("appletvos"));
    assertFalse(AbstractApplePlatform.APPLETVOS.getName().contains("appletvsimulator"));

    assertSame(AbstractApplePlatform.APPLETVSIMULATOR.getType(), ApplePlatformType.TV_SIMULATOR);
    assertFalse(AbstractApplePlatform.APPLETVSIMULATOR.getName().contains("osx"));
    assertFalse(AbstractApplePlatform.APPLETVSIMULATOR.getName().contains("iphoneos"));
    assertFalse(AbstractApplePlatform.APPLETVSIMULATOR.getName().contains("iphonesimulator"));
    assertFalse(AbstractApplePlatform.APPLETVSIMULATOR.getName().contains("watchos"));
    assertFalse(AbstractApplePlatform.APPLETVSIMULATOR.getName().contains("watchsimulator"));
    assertFalse(AbstractApplePlatform.APPLETVSIMULATOR.getName().contains("appletvos"));
    assertTrue(AbstractApplePlatform.APPLETVSIMULATOR.getName().contains("appletvsimulator"));
  }

  @Test
  public void watchTypes() {
    AbstractApplePlatform[] watchPlatforms = {
      AbstractApplePlatform.WATCHOS, AbstractApplePlatform.WATCHSIMULATOR
    };

    for (AbstractApplePlatform platform : watchPlatforms) {
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
