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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.AnsiEnvironmentChecking;
import java.util.Optional;
import org.immutables.value.Value;

@BuckStyleImmutable
@Value.Immutable(builder = false, copy = false)
public abstract class AbstractCliConfig implements ConfigView<BuckConfig> {
  @Override
  @Value.Parameter
  public abstract BuckConfig getDelegate();

  /**
   * Create an Ansi object appropriate for the current output. First respect the user's preferences,
   * if set. Next, respect any default provided by the caller. (This is used by buckd to tell the
   * daemon about the client's terminal.) Finally, allow the Ansi class to autodetect whether the
   * current output is a tty.
   *
   * @param defaultColor Default value provided by the caller (e.g. the client of buckd)
   */
  @Value.Derived
  public Ansi createAnsi(Optional<String> defaultColor) {
    String color =
        getDelegate().getValue("color", "ui").map(Optional::of).orElse(defaultColor).orElse("auto");

    switch (color) {
      case "false":
      case "never":
        return Ansi.withoutTty();
      case "true":
      case "always":
        return Ansi.forceTty();
      case "auto":
      default:
        return new Ansi(
            AnsiEnvironmentChecking.environmentSupportsAnsiEscapes(
                getDelegate().getPlatform(), getDelegate().getEnvironment()));
    }
  }
}
