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

package com.facebook.buck.util.network;

import com.facebook.buck.jvm.java.version.JavaVersion;
import com.facebook.buck.util.environment.Platform;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.NetworkInterface;

/**
 * When attempting to communicate with IPV6-only servers, Java 10 and earlier will attempt to do so
 * using the wrong network interface (AirDrop or Touch Bar instead of one's actual wifi or ethernet
 * interface). To work around this, we use Reflection to force the default interface to be 0, which
 * appears to mean "let the system choose" and results in the correct behavior.
 */
public class MacIpv6BugWorkaround {
  public static void apply() {
    if (Platform.detect() != Platform.MACOS || JavaVersion.getMajorVersion() >= 11) {
      return;
    }

    try {
      Field defaultIndex = NetworkInterface.class.getDeclaredField("defaultIndex");
      defaultIndex.setAccessible(true);

      Field modifiersField = Field.class.getDeclaredField("modifiers");
      modifiersField.setAccessible(true);
      modifiersField.setInt(defaultIndex, defaultIndex.getModifiers() & ~Modifier.FINAL);

      defaultIndex.setInt(null, 0);
    } catch (IllegalAccessException | NoSuchFieldException e) { // NOPMD this is really best-effort
      // Ignored
    }
  }
}
