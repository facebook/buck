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

package com.facebook.buck.skylark.parser;

import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.rules.providers.impl.BuiltInProvider;
import com.facebook.buck.core.starlark.compatible.BuckStarlark;
import com.facebook.buck.core.starlark.knowntypes.KnownUserDefinedRuleTypes;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.parser.options.ImplicitNativeRulesState;
import com.facebook.buck.parser.options.UserDefinedRulesState;
import com.facebook.buck.skylark.function.SkylarkBuiltInProviders;
import com.facebook.buck.skylark.function.SkylarkFunctionModule;
import com.facebook.buck.skylark.function.SkylarkProviderFunction;
import com.facebook.buck.skylark.function.SkylarkRuleFunctions;
import com.facebook.buck.skylark.function.attr.AttrModule;
import com.facebook.buck.skylark.function.packages.StructProvider;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.function.Function;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.Structure;
import org.immutables.value.Value.Lazy;

/**
 * Provides access to global Skylark interpreter frames.
 *
 * <p>It's recommended to use global frames for all globally accessible variables for improved
 * performance and easier maintenance, since there is only one place to check for variable
 * definitions.
 */
@BuckStyleValue
public abstract class BuckGlobals {

  abstract SkylarkFunctionModule getSkylarkFunctionModule();

  /** @return A set of rules supported by Buck. */
  abstract ImmutableSet<BaseDescription<?>> getDescriptions();

  /**
   * @return Whether implicit native rules should not be available in the context of extension file.
   */
  abstract ImplicitNativeRulesState getImplicitNativeRulesState();

  /**
   * @return Whether or not modules, providers, and other functions for user defined rules should be
   *     exported into .bzl files' execution environment
   */
  abstract UserDefinedRulesState getUserDefinedRulesState();

  /** @return A Skylark rule function factory. */
  abstract RuleFunctionFactory getRuleFunctionFactory();

  abstract KnownUserDefinedRuleTypes getKnownUserDefinedRuleTypes();

  abstract ImmutableSet<BuiltInProvider<?>> getPerFeatureProviders();

  /** bzl initial contents. */
  @Lazy
  protected ImmutableMap<String, Object> getBuckLoadContextGlobals() {
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
    addBuckGlobals(builder);
    builder.put("native", getNativeModule());
    builder.put("struct", StructProvider.STRUCT);
    if (getUserDefinedRulesState() == UserDefinedRulesState.ENABLED) {
      Starlark.addMethods(builder, new SkylarkRuleFunctions());
      builder.put("attr", new AttrModule());
      builder.putAll(SkylarkBuiltInProviders.PROVIDERS);
      builder.putAll(getPerFeatureProvidersForBuildFile());
    } else {
      // TODO(T48021397): provider() has some legacy behavior we'll need to migrate. The more
      // correct provider() is made available for user-defined rules in
      Starlark.addMethods(builder, new SkylarkProviderFunction());
    }
    addNativeModuleFunctions(builder);
    return builder.build();
  }

  /** Always disable implicit native imports in skylark rules, they should utilize native.foo */
  Module makeBuckLoadContextGlobals() {
    return Module.withPredeclared(
        BuckStarlark.BUCK_STARLARK_SEMANTICS, getBuckLoadContextGlobals());
  }

  /** {@code BUCK} file initial contents. */
  @Lazy
  protected ImmutableMap<String, Object> getMakeBuckBuildFileContextGlobals() {
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
    addBuckGlobals(builder);
    if (getImplicitNativeRulesState() == ImplicitNativeRulesState.ENABLED) {
      builder.putAll(getBuckRuleFunctions());
    }
    builder.put("native", getSkylarkFunctionModule());
    addNativeModuleFunctions(builder);
    return builder.build();
  }

  /** Disable implicit native rules depending on configuration */
  Module makeBuckBuildFileContextGlobals() {
    ImmutableMap<String, Object> map = getMakeBuckBuildFileContextGlobals();
    return Module.withPredeclared(BuckStarlark.BUCK_STARLARK_SEMANTICS, map);
  }

  /**
   * @return The list of functions supporting all native Buck functions like {@code java_library}.
   */
  @Lazy
  ImmutableMap<String, StarlarkCallable> getBuckRuleFunctions() {
    return getDescriptions().stream()
        .map(getRuleFunctionFactory()::create)
        .collect(ImmutableMap.toImmutableMap(StarlarkCallable::getName, r -> r));
  }

  /**
   * @return A mapping of all per-language providers that should be exported into build files and
   *     their names. e.g. {@code DotnetLibraryProviderInfo} -> {@code
   *     DotnetLibraryProviderInfo.PROVIDER}
   */
  @Lazy
  ImmutableMap<String, BuiltInProvider<?>> getPerFeatureProvidersForBuildFile() {
    return getPerFeatureProviders().stream()
        .collect(ImmutableMap.toImmutableMap(BuiltInProvider::getName, Function.identity()));
  }

  private Structure getNativeModule() {
    ImmutableMap.Builder<String, Object> builder = new ImmutableMap.Builder<>();
    builder.putAll(getBuckRuleFunctions());
    addNativeModuleFunctions(builder);
    return StructProvider.STRUCT.create(builder.build(), "no native function or rule '%s'");
  }

  /**
   * Returns a native module field names to be used in error suggestions.
   *
   * <p>"native" is the module that handles method calls like {@code native.glob} or {@code
   * native.cxx_library}.
   */
  @Lazy
  public ImmutableCollection<String> getNativeModuleFieldNames() {
    return getNativeModule().getFieldNames();
  }

  /** Puts all native module functions into provided {@code builder}. */
  private void addNativeModuleFunctions(ImmutableMap.Builder<String, Object> builder) {
    Starlark.addMethods(builder, getSkylarkFunctionModule());
  }

  /** Adds all buck globals to the provided {@code builder}. */
  private void addBuckGlobals(ImmutableMap.Builder<String, Object> builder) {
    builder.putAll(Starlark.UNIVERSE);
  }

  /** Create an instance of {@link BuckGlobals} */
  public static BuckGlobals of(
      SkylarkFunctionModule skylarkFunctionModule,
      ImmutableSet<BaseDescription<?>> descriptions,
      UserDefinedRulesState userDefinedRulesState,
      ImplicitNativeRulesState implicitNativeRulesState,
      RuleFunctionFactory ruleFunctionFactory,
      KnownUserDefinedRuleTypes knownUserDefinedRuleTypes,
      ImmutableSet<BuiltInProvider<?>> perFeatureProviders) {
    return ImmutableBuckGlobals.ofImpl(
        skylarkFunctionModule,
        descriptions,
        implicitNativeRulesState,
        userDefinedRulesState,
        ruleFunctionFactory,
        knownUserDefinedRuleTypes,
        perFeatureProviders);
  }
}
