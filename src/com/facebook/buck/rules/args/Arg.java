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
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

/**
 * An abstraction for modeling the arguments that contribute to a command run by a
 * {@link BuildRule}.
 */
public abstract class Arg implements RuleKeyAppendable {

  private static final Function<Arg, ImmutableList<String>> STRINGIFY_LIST =
      new Function<Arg, ImmutableList<String>>() {
        @Override
        public ImmutableList<String> apply(Arg input) {
          ImmutableList.Builder<String> builder = ImmutableList.builder();
          input.appendToCommandLine(builder);
          return builder.build();
        }
      };

  /**
   * @return any {@link BuildRule}s that need to be built before this argument can be used.
   */
  public abstract ImmutableCollection<BuildRule> getDeps(SourcePathResolver resolver);

  /**
   * Append the contents of the Arg to the supplied builder. This call may inject any number
   * of elements (including zero) into the builder. This is only ever safe to call when the
   * rule is running, as it may do things like resolving source paths.
   */
  public abstract void appendToCommandLine(ImmutableCollection.Builder<String> builder);

  /**
   * @return a {@link String} representation suitable to use for debugging.
   */
  @Override
  public abstract String toString();

  @Override
  public abstract boolean equals(Object other);

  @Override
  public abstract int hashCode();

  public static Function<Arg, ImmutableCollection<BuildRule>> getDepsFunction(
      final SourcePathResolver resolver) {
    return new Function<Arg, ImmutableCollection<BuildRule>>() {
      @Override
      public ImmutableCollection<BuildRule> apply(Arg arg) {
        return arg.getDeps(resolver);
      }
    };
  }

  public static Function<Arg, ImmutableList<String>> stringListFunction() {
    return STRINGIFY_LIST;
  }

  public static ImmutableList<String> stringify(ImmutableCollection<Arg> args) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    for (Arg arg : args) {
      arg.appendToCommandLine(builder);
    }
    return builder.build();
  }
}
