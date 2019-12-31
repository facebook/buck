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

package com.facebook.buck.rules.args;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.Iterables;
import java.util.Arrays;
import java.util.function.Consumer;

@BuckStyleValue
public abstract class StringArg implements Arg {
  @AddToRuleKey
  public abstract String getArg();

  @Override
  public void appendToCommandLine(
      Consumer<String> consumer, SourcePathResolverAdapter pathResolver) {
    consumer.accept(getArg());
  }

  public static Iterable<Arg> from(Iterable<String> args) {
    return Iterables.transform(args, StringArg::of);
  }

  public static Iterable<Arg> from(String... args) {
    return from(Arrays.asList(args));
  }

  public static StringArg of(String arg) {
    return ImmutableStringArg.of(arg);
  }
}
