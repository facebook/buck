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
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.util.Objects;

public class StringArg extends Arg {

  private final String arg;

  public StringArg(String arg) {
    this.arg = arg;
  }

  @Override
  public String stringify() {
    return arg;
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathResolver resolver) {
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

  public static ImmutableList<Arg> from(Iterable<String> args) {
    ImmutableList.Builder<Arg> converted = ImmutableList.builder();
    for (String arg : args) {
      converted.add(new StringArg(arg));
    }
    return converted.build();
  }

  public static ImmutableList<Arg> from(String... args) {
    return from(ImmutableList.copyOf(args));
  }

}
