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

package com.facebook.buck.support.cli.config;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.AnsiEnvironmentChecking;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.immutables.value.Value;

@BuckStyleImmutable
@Value.Immutable(builder = false, copy = false)
public abstract class AbstractCliConfig implements ConfigView<BuckConfig> {

  private static final String UI_SECTION = "ui";

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

  /** When printing out json representation of targets, what formatting should be applied */
  @Value.Derived
  public JsonAttributeFormat getJsonAttributeFormat() {
    return getDelegate()
        .getEnum(UI_SECTION, "json_attribute_format", JsonAttributeFormat.class)
        .orElse(JsonAttributeFormat.DEFAULT);
  }

  @Value.Lazy
  public boolean getWarnOnConfigFileOverrides() {
    return getDelegate().getBooleanValue(UI_SECTION, "warn_on_config_file_overrides", true);
  }

  @Value.Lazy
  public ImmutableSet<Path> getWarnOnConfigFileOverridesIgnoredFiles() {
    return getDelegate()
        .getListWithoutComments(UI_SECTION, "warn_on_config_file_overrides_ignored_files", ',')
        .stream()
        .map(Paths::get)
        .collect(ImmutableSet.toImmutableSet());
  }

  @Value.Lazy
  public boolean getFlushEventsBeforeExit() {
    return getDelegate().getBooleanValue("daemon", "flush_events_before_exit", false);
  }

  @Value.Lazy
  public ImmutableList<String> getMessageOfTheDay() {
    return getDelegate().getListWithoutComments("project", "motd");
  }
}
