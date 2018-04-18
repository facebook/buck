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

package com.facebook.buck.skylark.parser.context;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Tracks parse context.
 *
 * <p>This class provides API to record information retrieved while parsing a build file like parsed
 * rules.
 */
public class ParseContext {
  private final ImmutableList.Builder<ImmutableMap<String, Object>> rawRuleBuilder;
  // stores every accessed configuration option while parsing the build file.
  // the schema is: section->key->value
  private final Map<String, Map<String, Optional<String>>> readConfigOptions;
  // internal variable exposed to rules that is used to track parse events. This allows us to
  // remove parse state from rules and as such makes rules reusable across parse invocations
  private static final String PARSE_CONTEXT = "$parse_context";

  public ParseContext() {
    rawRuleBuilder = ImmutableList.builder();
    readConfigOptions = new ConcurrentHashMap<>();
  }

  /** Records the parsed {@code rawRule}. */
  public void recordRule(ImmutableMap<String, Object> rawRule) {
    rawRuleBuilder.add(rawRule);
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

  /**
   * @return The list of raw build rules discovered in parsed build file. Raw rule is presented as a
   *     map with attributes as keys and parameters as values.
   */
  public ImmutableList<ImmutableMap<String, Object>> getRecordedRules() {
    return rawRuleBuilder.build();
  }

  public ImmutableMap<String, ImmutableMap<String, Optional<String>>>
      getAccessedConfigurationOptions() {
    return readConfigOptions
        .entrySet()
        .stream()
        .collect(
            ImmutableMap.toImmutableMap(Entry::getKey, e -> ImmutableMap.copyOf(e.getValue())));
  }

  /** Get the {@link ParseContext} by looking up in the environment. */
  public static ParseContext getParseContext(Environment env, FuncallExpression ast)
      throws EvalException {
    @Nullable ParseContext value = (ParseContext) env.lookup(PARSE_CONTEXT);
    if (value == null) {
      // if PARSE_CONTEXT is missing, we're not called from a build file. This happens if someone
      // uses native.some_func() in the wrong place.
      throw new EvalException(
          ast.getLocation(),
          "The native module cannot be accessed from here. "
              + "Wrap the function in a macro and call it from a BUCK file");
    }
    return value;
  }

  public void setup(Environment env) {
    env.setupDynamic(PARSE_CONTEXT, this);
  }
}
