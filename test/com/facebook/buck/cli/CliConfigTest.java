/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import java.util.Optional;
import org.junit.Test;

public class CliConfigTest {
  @Test
  public void testCreateAnsiForWindows() {
    assumeThat(Platform.detect(), is(Platform.WINDOWS));
    CliConfig windowsConfig =
        FakeBuckConfig.builder()
            .setArchitecture(Architecture.X86_64)
            .setPlatform(Platform.WINDOWS)
            .build()
            .getView(CliConfig.class);
    // "auto" on Windows is equivalent to "never".
    assertFalse(windowsConfig.createAnsi(Optional.empty()).isAnsiTerminal());
    assertFalse(windowsConfig.createAnsi(Optional.of("auto")).isAnsiTerminal());
    assertTrue(windowsConfig.createAnsi(Optional.of("always")).isAnsiTerminal());
    assertFalse(windowsConfig.createAnsi(Optional.of("never")).isAnsiTerminal());
  }

  @Test
  public void testCreateAnsiForLinux() {
    assumeThat(Platform.detect(), is(Platform.LINUX));
    CliConfig linuxConfig =
        FakeBuckConfig.builder()
            .setArchitecture(Architecture.I386)
            .setPlatform(Platform.LINUX)
            .build()
            .getView(CliConfig.class);
    // We don't test "auto" on Linux, because the behavior would depend on how the test was run.
    assertTrue(linuxConfig.createAnsi(Optional.of("always")).isAnsiTerminal());
    assertFalse(linuxConfig.createAnsi(Optional.of("never")).isAnsiTerminal());
  }
}
