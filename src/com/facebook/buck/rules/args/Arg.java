/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rules.args;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;

/**
 * An abstraction for modeling the arguments that contribute to a command run by a {@link
 * BuildRule}, and also carry information for computing a rule key.
 */
public interface Arg extends RuleKeyAppendable {

  /** @return any {@link BuildRule}s that need to be built before this argument can be used. */
  ImmutableCollection<BuildRule> getDeps(SourcePathRuleFinder ruleFinder);

  /** @return any {@link BuildRule}s that need to be built before this argument can be used. */
  ImmutableCollection<SourcePath> getInputs();

  /**
   * Append the contents of the Arg to the supplied builder. This call may inject any number of
   * elements (including zero) into the builder. This is only ever safe to call when the rule is
   * running, as it may do things like resolving source paths.
   */
  void appendToCommandLine(
      ImmutableCollection.Builder<String> builder, SourcePathResolver pathResolver);

  /** @return a {@link String} representation suitable to use for debugging. */
  @Override
  String toString();

  @Override
  boolean equals(Object other);

  @Override
  int hashCode();

  static ImmutableList<String> stringifyList(Arg input, SourcePathResolver pathResolver) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    input.appendToCommandLine(builder, pathResolver);
    return builder.build();
  }

  static ImmutableList<String> stringify(
      ImmutableCollection<Arg> args, SourcePathResolver pathResolver) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    for (Arg arg : args) {
      arg.appendToCommandLine(builder, pathResolver);
    }
    return builder.build();
  }

  static <K> ImmutableMap<K, String> stringify(
      ImmutableMap<K, Arg> argMap, SourcePathResolver pathResolver) {
    ImmutableMap.Builder<K, String> stringMap = ImmutableMap.builder();
    for (Map.Entry<K, Arg> ent : argMap.entrySet()) {
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      ent.getValue().appendToCommandLine(builder, pathResolver);
      stringMap.put(ent.getKey(), Joiner.on(" ").join(builder.build()));
    }
    return stringMap.build();
  }
}
