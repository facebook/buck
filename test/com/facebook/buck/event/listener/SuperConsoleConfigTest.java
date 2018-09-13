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

package com.facebook.buck.event.listener;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;

public class SuperConsoleConfigTest {

  @Test
  public void testIsEnabled() {
    Console nonAnsiConsole =
        new Console(
            Verbosity.STANDARD_INFORMATION,
            new CapturingPrintStream(),
            new CapturingPrintStream(),
            Ansi.withoutTty());
    Console ansiConsole =
        new Console(
            Verbosity.STANDARD_INFORMATION,
            new CapturingPrintStream(),
            new CapturingPrintStream(),
            Ansi.forceTty());

    SuperConsoleConfig enabledConfig = createConfigWithSuperConsoleValue("enabled");
    assertTrue(enabledConfig.isEnabled(null, Platform.LINUX));

    SuperConsoleConfig disabledConfig = createConfigWithSuperConsoleValue("disabled");
    assertFalse(disabledConfig.isEnabled(null, Platform.LINUX));

    SuperConsoleConfig autoConfig = createConfigWithSuperConsoleValue("auto");
    assertTrue(autoConfig.isEnabled(ansiConsole, Platform.LINUX));
    assertFalse(autoConfig.isEnabled(nonAnsiConsole, Platform.LINUX));
    assertTrue(autoConfig.isEnabled(ansiConsole, Platform.WINDOWS));

    SuperConsoleConfig emptyConfig = new SuperConsoleConfig(FakeBuckConfig.builder().build());
    assertTrue(emptyConfig.isEnabled(ansiConsole, Platform.LINUX));
    assertFalse(emptyConfig.isEnabled(nonAnsiConsole, Platform.LINUX));
    assertTrue(emptyConfig.isEnabled(ansiConsole, Platform.WINDOWS));
  }

  private SuperConsoleConfig createConfigWithSuperConsoleValue(String enabled) {
    BuckConfig enabledConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("ui", ImmutableMap.of("superconsole", enabled)))
            .build();
    return new SuperConsoleConfig(enabledConfig);
  }
}
