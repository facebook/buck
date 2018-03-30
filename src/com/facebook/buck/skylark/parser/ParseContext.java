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

package com.facebook.buck.skylark.parser;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Tracks parse context.
 *
 * <p>This class provides API to record information retrieved while parsing a build file like parsed
 * rules.
 */
class ParseContext {
  private final ImmutableList.Builder<Map<String, Object>> rawRuleBuilder;
  // internal variable exposed to rules that is used to track parse events. This allows us to
  // remove parse state from rules and as such makes rules reusable across parse invocations
  private static final String PARSE_CONTEXT = "$parse_context";

  ParseContext() {
    rawRuleBuilder = ImmutableList.builder();
  }

  /** Records the parsed {@code rawRule}. */
  void recordRule(Map<String, Object> rawRule) {
    rawRuleBuilder.add(rawRule);
  }

  /**
   * @return The list of raw build rules discovered in parsed build file. Raw rule is presented as a
   *     map with attributes as keys and parameters as values.
   */
  ImmutableList<Map<String, Object>> getRecordedRules() {
    return rawRuleBuilder.build();
  }

  /** Get the {@link ParseContext} by looking up in the environment. */
  static ParseContext getParseContext(Environment env, FuncallExpression ast) throws EvalException {
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
