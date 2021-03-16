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

package com.facebook.buck.skylark.parser.context;

import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkThread;

/** Context needed for {@code read_config} function. */
public class ReadConfigContext {
  private final ImmutableMap<String, ImmutableMap<String, String>> rawConfig;

  // stores every accessed configuration option while parsing the build file.
  // the schema is: section->key->value
  private final Map<String, Map<String, Optional<String>>> readConfigOptions;

  public ReadConfigContext(ImmutableMap<String, ImmutableMap<String, String>> rawConfig) {
    this.rawConfig = rawConfig;
    this.readConfigOptions = new HashMap<>();
  }

  /**
   * Records an accessed {@code section.key} configuration and its returned {@code value}.
   *
   * <p>It's safe to not have to override existing values because configuration options are frozen
   * for the duration of build file parsing.
   */
  public void recordReadConfigurationOption(String section, String key, @Nullable String value) {
    readConfigOptions
        .computeIfAbsent(section, s -> new HashMap<>())
        .putIfAbsent(key, Optional.ofNullable(value));
  }

  public ImmutableMap<String, ImmutableMap<String, Optional<String>>>
      getAccessedConfigurationOptions() {
    return readConfigOptions.entrySet().stream()
        .collect(
            ImmutableMap.toImmutableMap(Map.Entry::getKey, e -> ImmutableMap.copyOf(e.getValue())));
  }

  public ImmutableMap<String, ImmutableMap<String, String>> getRawConfig() {
    return rawConfig;
  }

  /** Get the {@link ParseContext} by looking up in the environment. */
  public static ReadConfigContext getContext(StarlarkThread env) throws EvalException {
    @Nullable ReadConfigContext value = env.getThreadLocal(ReadConfigContext.class);
    if (value == null) {
      // If PARSE_CONTEXT is missing, we exported `read_config` function, but
      // did not export `ReadConfigContext`. This should not happen since
      // we enabled `read_config` in top-level `.bzl`.
      throw new EvalException("Context is not set for read_config function.");
    }
    return value;
  }

  public void setup(StarlarkThread env) {
    env.setThreadLocal(ReadConfigContext.class, this);
  }
}
