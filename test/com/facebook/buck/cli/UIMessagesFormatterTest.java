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

import static com.facebook.buck.cli.UIMessagesFormatter.COMPARISON_KEY_PREFIX;
import static com.facebook.buck.cli.UIMessagesFormatter.COMPARISON_MESSAGE_PREFIX;
import static com.facebook.buck.cli.UIMessagesFormatter.USING_ADDITIONAL_CONFIGURATION_OPTIONS_PREFIX;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.support.cli.args.GlobalCliOptions;
import com.facebook.buck.util.config.Configs;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class UIMessagesFormatterTest {

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void useSpecificOverridesMessage() throws IOException {
    Path tempPath = temporaryFolder.newFolder().toPath();
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
    Optional<String> message = UIMessagesFormatter.configComparisonMessage(ImmutableSet.of());
    assertFalse(message.isPresent());
  }

  @Test
  public void configComparisonMessageWithNumberOfDiffsUnderDisplayLimit() {
    Optional<String> message =
        UIMessagesFormatter.configComparisonMessage(ImmutableSet.of("diff1", "diff2"));
    assertTrue(message.isPresent());
    assertEquals(message.get(), formatExpectedComparisonMessage("diff1", "diff2"));
  }

  @Test
  public void configComparisonMessageWithNumberOfDiffsAboveDisplayLimit() {
    Optional<String> message =
        UIMessagesFormatter.configComparisonMessage(
            ImmutableSet.of("diff1", "diff2", "diff3", "diff4", "diff5"));
    assertTrue(message.isPresent());
    assertEquals(
        message.get(),
        formatExpectedComparisonMessage(Optional.of("... and 2 more."), "diff1", "diff2", "diff3"));
  }

  @Test
  public void configComparisonMessageWithNumberOfDiffsEqualsToDisplayLimit() {
    Optional<String> message =
        UIMessagesFormatter.configComparisonMessage(ImmutableSet.of("diff1", "diff2", "diff3"));
    assertTrue(message.isPresent());
    assertEquals(message.get(), formatExpectedComparisonMessage("diff1", "diff2", "diff3"));
  }

  private String formatExpectedComparisonMessage(String... diffs) {
    return formatExpectedComparisonMessage(Optional.empty(), diffs);
  }

  private String formatExpectedComparisonMessage(Optional<String> suffix, String... diffs) {
    StringBuilder sb = new StringBuilder(COMPARISON_MESSAGE_PREFIX);
    sb.append(System.lineSeparator());
    for (String diff : diffs) {
      sb.append(COMPARISON_KEY_PREFIX);
      sb.append(diff);
      sb.append(System.lineSeparator());
    }

    suffix.ifPresent(
        suffixValue -> {
          sb.append(COMPARISON_KEY_PREFIX);
          sb.append(suffixValue);
        });
    return sb.toString();
  }
}
