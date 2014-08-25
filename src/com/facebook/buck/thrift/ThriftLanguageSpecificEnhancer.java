/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.thrift;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

/**
 * Interface used to implement thrift support for a language.  {@link ThriftLibraryDescription}
 * objects will use these for the various languages it supports.
 */
public interface ThriftLanguageSpecificEnhancer {

  /**
   * @return the language name to pass to thrift's "--gen" option.
   */
  String getLanguage();

  /**
   * @return the flavor used to reference this language via the target graph.
   */
  Flavor getFlavor();

  /**
   * @param sources objects representing the thrift sources that are being compiled for
   *     this language, and the {@link ThriftCompiler} rules that do the compiling.
   * @param deps the language specific dependencies.
   * @return a {@link BuildRule} which performs the language specific build.
   */
  BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      ThriftConstructorArg args,
      ImmutableMap<String, ThriftSource> sources,
      ImmutableSortedSet<BuildRule> deps);

  /**
   * @return the names of extra dependencies implicitly required for this language.
   *     Note that we require two definitions of this method since we need to lookup the
   *     the implicit deps a) when constructing the target graph and b) when building the
   *     actions for the action graph.
   */
  ImmutableSet<BuildTarget> getImplicitDepsFromParams(BuildRuleFactoryParams params);
  ImmutableSet<BuildTarget> getImplicitDepsFromArg(BuildTarget target, ThriftConstructorArg args);

  /**
   * @return the language specific options to pass to the thrift compiler.
   */
  ImmutableSet<String> getOptions(BuildTarget target, ThriftConstructorArg arg);

}
