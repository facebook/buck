/*
 * Copyright 2019-present Facebook, Inc.
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
import com.facebook.buck.support.cli.args.GlobalCliOptions;
import com.facebook.buck.util.config.Configs;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** Formats messages that will be displayed in console */
class UIMessagesFormatter {

  private static final int MAX_LINES_IN_COMPARISON = 3;
  static final String COMPARISON_KEY_PREFIX = "\t-\t";
  static final String COMPARISON_MESSAGE_PREFIX =
      "Running with reused config, some configuration changes would not be applied: ";
  static final String USING_ADDITIONAL_CONFIGURATION_OPTIONS_PREFIX =
      "Using additional configuration options from ";

  /** Formats comparison of {@link BuckConfig}s into UI message */
  static Optional<String> configComparisonMessage(ImmutableSet<String> diff) {
    if (diff.isEmpty()) {
      return Optional.empty();
    }

    StringBuilder diffBuilder = new StringBuilder(COMPARISON_MESSAGE_PREFIX);
    diffBuilder.append(System.lineSeparator());

    diff.stream()
        .limit(MAX_LINES_IN_COMPARISON)
        .forEach(
            diffValue ->
                diffBuilder
                    .append(COMPARISON_KEY_PREFIX)
                    .append(diffValue)
                    .append(System.lineSeparator()));

    if (diff.size() > MAX_LINES_IN_COMPARISON) {
      diffBuilder
          .append(COMPARISON_KEY_PREFIX)
          .append("... and ")
          .append(diff.size() - MAX_LINES_IN_COMPARISON)
          .append(" more.");
    }

    return Optional.of(diffBuilder.toString());
  }

  static String reuseConfigPropertyProvidedMessage() {
    return String.format(
        "`%s` parameter provided. Reusing previously defined config.",
        GlobalCliOptions.REUSE_CURRENT_CONFIG_ARG);
  }

  static Optional<String> useSpecificOverridesMessage(
      Path root, ImmutableSet<Path> overridesToIgnore) throws IOException {

    Path mainConfigPath = Configs.getMainConfigurationFile(root);
    String userSpecifiedOverrides =
        Configs.getDefaultConfigurationFiles(root).stream()
            .filter(path -> isValidPath(path, overridesToIgnore, mainConfigPath))
            .map(path -> path.startsWith(root) ? root.relativize(path) : path)
            .map(Objects::toString)
            .distinct()
            .sorted(Comparator.naturalOrder())
            .collect(Collectors.joining(", "));

    return Optional.of(userSpecifiedOverrides)
        .filter(Predicates.not(String::isEmpty))
        .map(USING_ADDITIONAL_CONFIGURATION_OPTIONS_PREFIX::concat);
  }

  private static boolean isValidPath(
      Path path, ImmutableSet<Path> overridesToIgnore, Path mainConfigPath) {
    return !overridesToIgnore.contains(path.getFileName()) && !mainConfigPath.equals(path);
  }
}
