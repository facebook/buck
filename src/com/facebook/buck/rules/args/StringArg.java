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
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import java.util.Arrays;
import java.util.Objects;

public class StringArg extends Arg {

  private final String arg;

  private static final Function<String, Arg> CONVERT =
      new Function<String, Arg>() {
        @Override
        public Arg apply(String input) { return new StringArg(input); }
      };

  public StringArg(String arg) {
    this.arg = arg;
  }

  @Override
  public void appendToCommandLine(ImmutableCollection.Builder<String> builder) {
    builder.add(arg);
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathResolver resolver) {
    return ImmutableList.of();
  }

  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    return ImmutableList.of();
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    return builder.setReflectively("arg", arg);
  }

  @Override
  public String toString() {
    return arg;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof StringArg)) {
      return false;
    }
    StringArg stringArg = (StringArg) o;
    return Objects.equals(arg, stringArg.arg);
  }

  @Override
  public int hashCode() {
    return Objects.hash(arg);
  }

  public static Iterable<Arg> from(Iterable<String> args) {
    return Iterables.transform(args, CONVERT);
  }

  public static Iterable<Arg> from(String... args) {
    return from(Arrays.asList(args));
  }
}
