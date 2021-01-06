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

package com.facebook.buck.cli;

import static com.facebook.buck.cli.UIMessagesFormatter.COMPARISON_MESSAGE_PREFIX;
import static com.facebook.buck.cli.UIMessagesFormatter.USING_ADDITIONAL_CONFIGURATION_OPTIONS_PREFIX;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.command.config.ConfigDifference.ConfigChange;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.support.cli.args.GlobalCliOptions;
import com.facebook.buck.util.config.Configs;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class UIMessagesFormatterTest {

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void useSpecificOverridesMessage() throws IOException {
    AbsPath tempPath = AbsPath.of(temporaryFolder.newFolder().toPath());
    Optional<String> message =
        UIMessagesFormatter.useSpecificOverridesMessage(tempPath, ImmutableSet.of());
    if (!Configs.getDefaultConfigurationFiles(tempPath).isEmpty()) {
      assertTrue(message.isPresent());
      assertThat(message.get(), startsWith(USING_ADDITIONAL_CONFIGURATION_OPTIONS_PREFIX));
    } else {
      assertFalse(message.isPresent());
    }
  }

  @Test
  public void reuseConfigPropertyProvidedMessage() {
    String message = UIMessagesFormatter.reuseConfigPropertyProvidedMessage();
    assertThat(message, containsString(GlobalCliOptions.REUSE_CURRENT_CONFIG_ARG));
  }

  @Test
  public void configComparisonMessageWithEmptyNumberOfDiffs() {
    Optional<String> message = UIMessagesFormatter.reusedConfigWarning(ImmutableMap.of());
    assertFalse(message.isPresent());
  }

  @Test
  public void configComparisonMessageWithNumberOfDiffsUnderDisplayLimit() {
    Optional<String> message = UIMessagesFormatter.reusedConfigWarning(generateConfigChange(2));
    assertTrue(message.isPresent());
    assertEquals(message.get(), formatExpectedComparisonMessage(generateConfigChange(2)));
  }

  @Test
  public void configComparisonMessageWithNumberOfDiffsAboveDisplayLimit() {
    Optional<String> message = UIMessagesFormatter.reusedConfigWarning(generateConfigChange(5));
    assertTrue(message.isPresent());
    assertEquals(
        message.get(),
        formatExpectedComparisonMessage(
            Optional.of("... and 2 more. See logs for all changes"), generateConfigChange(3)));
  }

  /** Generates set of changes that look like: diff{i}: before{i} -> after{i} for i = 1 to n */
  private static Map<String, ConfigChange> generateConfigChange(int n) {
    ImmutableMap.Builder<String, ConfigChange> builder = ImmutableMap.builder();
    IntStream.range(0, n)
        .forEach((i) -> builder.put("diff" + i, ConfigChange.of("before" + i, "after" + i)));
    return builder.build();
  }

  @Test
  public void configComparisonMessageWithNumberOfDiffsEqualsToDisplayLimit() {
    Optional<String> message = UIMessagesFormatter.reusedConfigWarning(generateConfigChange(3));
    assertTrue(message.isPresent());
    assertEquals(message.get(), formatExpectedComparisonMessage(generateConfigChange(3)));
  }

  private String formatExpectedComparisonMessage(Map<String, ConfigChange> diffs) {
    return formatExpectedComparisonMessage(Optional.empty(), diffs);
  }

  private String formatExpectedComparisonMessage(
      Optional<String> suffix, Map<String, ConfigChange> diffs) {
    StringBuilder sb = new StringBuilder(COMPARISON_MESSAGE_PREFIX);
    sb.append(System.lineSeparator() + "  ");
    sb.append(
        diffs.entrySet().stream()
            .map(
                (entry) ->
                    String.format(
                        "Changed value %s='%s' (was '%s')",
                        entry.getKey(),
                        entry.getValue().getNewValue(),
                        entry.getValue().getPrevValue()))
            .collect(Collectors.joining(System.lineSeparator() + "  ")));

    suffix.ifPresent(
        suffixValue -> {
          sb.append(System.lineSeparator() + "  ");
          sb.append(suffixValue);
        });
    return sb.toString();
  }
}
