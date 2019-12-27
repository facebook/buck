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

package com.facebook.buck.core.exceptions.config;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;
import org.immutables.value.Value;

@BuckStyleValue
public abstract class ErrorHandlingBuckConfig implements ConfigView<BuckConfig> {

  public static ErrorHandlingBuckConfig of(BuckConfig delegate) {
    return ImmutableErrorHandlingBuckConfig.of(delegate);
  }

  @Override
  public abstract BuckConfig getDelegate();

  /** List of error message replacements to make things more friendly for humans */
  @Value.Lazy
  public Map<Pattern, String> getErrorMessageAugmentations() throws HumanReadableException {
    return getDelegate().getMap("ui", "error_message_augmentations").entrySet().stream()
        .collect(
            ImmutableMap.toImmutableMap(
                e -> {
                  try {
                    return Pattern.compile(e.getKey(), Pattern.MULTILINE | Pattern.DOTALL);
                  } catch (Exception ex) {
                    throw new HumanReadableException(
                        "Could not parse regular expression %s from buckconfig: %s",
                        e.getKey(), ex.getMessage());
                  }
                },
                Entry::getValue));
  }
}
