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

package com.facebook.buck.core.exceptions.handler;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A class that can append additional information to a HumanReadableException based on configuration
 * settings. This can be useful, e.g., when there's a common parse error whose cause is not
 * immediately visible.
 */
public class HumanReadableExceptionAugmentor {

  private final Map<Pattern, String> augmentations;
  private static final Pattern REMOVE_COLOR_REGEX = Pattern.compile("\u001B\\[[;\\d]*m");

  /**
   * Create an instance of {@link HumanReadableExceptionAugmentor}
   *
   * @param augmentations A mapping of regexes to the replacement strings to append to the end of
   *     error messages
   */
  public HumanReadableExceptionAugmentor(Map<Pattern, String> augmentations) {
    this.augmentations = augmentations;
  }

  /**
   * Adds messages to the end of an existing error message based on regular expressions provided in
   * .buckconfig.
   *
   * <p>If replacement fails (due to a configuration error, such as invalid regex), then a warning
   * will be added to the returned message
   *
   * @return The original error message, with any error augmentations that this message matches
   *     appended to the end
   */
  public String getAugmentedError(String humanReadableErrorMessage) {
    if (augmentations.size() == 0) {
      return humanReadableErrorMessage;
    }

    String decolored = REMOVE_COLOR_REGEX.matcher(humanReadableErrorMessage).replaceAll("");
    StringBuilder ret = new StringBuilder(humanReadableErrorMessage);

    for (Map.Entry<Pattern, String> augmentation : augmentations.entrySet()) {
      Matcher matcher = augmentation.getKey().matcher(decolored);
      if (matcher.find()) {
        // We want to keep the original string as is, and use the match as the basis for replacement
        // (so that people can use backrefs in their messages)
        try {
          Matcher innerMatcher = augmentation.getKey().matcher(matcher.group(0));
          String replacement = innerMatcher.replaceAll(augmentation.getValue());
          ret.append(System.lineSeparator());
          ret.append(replacement);
        } catch (Exception e) {
          ret.append(
              String.format(
                  System.lineSeparator() + "Could not replace text \"%s\" with regex \"%s\": %s",
                  matcher.group(0),
                  augmentation.getValue(),
                  e.getMessage()));
        }
      }
    }
    return ret.toString();
  }
}
