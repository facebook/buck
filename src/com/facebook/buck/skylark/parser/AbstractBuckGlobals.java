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

package com.facebook.buck.skylark.parser;

import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.skylark.function.SkylarkNativeModule;
import com.facebook.buck.skylark.function.SkylarkRuleFunctions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.packages.BazelLibrary;
import com.google.devtools.build.lib.packages.StructProvider;
import com.google.devtools.build.lib.syntax.BuiltinFunction;
import com.google.devtools.build.lib.syntax.ClassObject;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.Runtime;
import org.immutables.value.Value;
import org.immutables.value.Value.Lazy;

/**
 * Provides access to global Skylark interpreter frames.
 *
 * <p>It's recommended to use global frames for all globally accessible variables for improved
 * performance and easier maintenance, since there is only one place to check for variable
 * definitions.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractBuckGlobals {

  /** Always disable implicit native imports in skylark rules, they should utilize native.foo */
  @Lazy
  Environment.GlobalFrame getBuckLoadContextGlobals() {
    try (Mutability mutability = Mutability.create("global_load_ctx")) {
      Environment extensionEnv =
          Environment.builder(mutability)
              .useDefaultSemantics()
              .setGlobals(getBuckGlobals(true))
              .build();
      extensionEnv.setup("native", getNativeModule());
      extensionEnv.setup("struct", StructProvider.STRUCT);
      // TODO(ttsugrii): switch to Runtime.setupSkylarkLibrary
      Runtime.setupModuleGlobals(extensionEnv, SkylarkRuleFunctions.class);
      return extensionEnv.getGlobals();
    }
  }

  /** Disable implicit native rules depending on configuration */
  @Lazy
  Environment.GlobalFrame getBuckBuildFileContextGlobals() {
    return getBuckGlobals(getDisableImplicitNativeRules());
  }

  /**
   * @return Whether implicit native rules should not be available in the context of extension file.
   */
  abstract boolean getDisableImplicitNativeRules();

  /** @return A Skylark rule function factory. */
  abstract RuleFunctionFactory getRuleFunctionFactory();

  /** @return A set of rules supported by Buck. */
  abstract ImmutableSet<BaseDescription<?>> getDescriptions();

  /**
   * @return The list of functions supporting all native Buck functions like {@code java_library}.
   */
  @Lazy
  ImmutableList<BuiltinFunction> getBuckRuleFunctions() {
    return getDescriptions()
        .stream()
        .map(getRuleFunctionFactory()::create)
        .collect(ImmutableList.toImmutableList());
  }

  /**
   * Returns a native module with built-in functions and Buck rules.
   *
   * <p>It's the module that handles method calls like {@code native.glob} or {@code
   * native.cxx_library}.
   */
  @Lazy
  ClassObject getNativeModule() {
    ImmutableMap.Builder<String, Object> builder = new ImmutableMap.Builder<>();
    for (BuiltinFunction ruleFunction : getBuckRuleFunctions()) {
      builder.put(ruleFunction.getName(), ruleFunction);
    }
    for (String nativeFunction : FuncallExpression.getMethodNames(SkylarkNativeModule.class)) {
      builder.put(
          nativeFunction,
          FuncallExpression.getBuiltinCallable(SkylarkNativeModule.NATIVE_MODULE, nativeFunction));
    }
    return StructProvider.STRUCT.create(builder.build(), "no native function or rule '%s'");
  }

  /**
   * @return The environment frame with configured buck globals. This includes built-in rules like
   *     {@code java_library}.
   * @param disableImplicitNativeRules If true, do not export native rules into the provided context
   */
  private Environment.GlobalFrame getBuckGlobals(boolean disableImplicitNativeRules) {
    try (Mutability mutability = Mutability.create("global")) {
      Environment globalEnv =
          Environment.builder(mutability)
              .setGlobals(BazelLibrary.GLOBALS)
              .useDefaultSemantics()
              .build();

      if (!disableImplicitNativeRules) {
        for (BuiltinFunction buckRuleFunction : getBuckRuleFunctions()) {
          globalEnv.setup(buckRuleFunction.getName(), buckRuleFunction);
        }
      }
      return globalEnv.getGlobals();
    }
  }
}
