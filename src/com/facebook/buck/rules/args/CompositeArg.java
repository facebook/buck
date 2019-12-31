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

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableList;
import java.util.function.Consumer;

/**
 * CompositeArg holds a list of args and appends them all to the command-line. It does not add any
 * separator between the args, so if that's necessary it should be added via StringArgs in the list
 * of Args.
 */
@BuckStyleValue
public abstract class CompositeArg implements Arg {
  @AddToRuleKey
  abstract ImmutableList<Arg> getArgs();

  @Override
  public void appendToCommandLine(
      Consumer<String> consumer, SourcePathResolverAdapter pathResolver) {
    StringBuilder builder = new StringBuilder();
    getArgs().forEach(arg -> arg.appendToCommandLine(builder::append, pathResolver));
    consumer.accept(builder.toString());
  }

  public void appendToCommandLineRel(
      Consumer<String> consumer,
      CanonicalCellName cellName,
      SourcePathResolverAdapter pathResolver,
      boolean useUnixPathSeparator) {
    ImmutableList<Arg> args = getArgs();
    StringBuilder builder = new StringBuilder();
    for (Arg arg : args) {
      if (arg instanceof SourcePathArg) {
        ((SourcePathArg) arg)
            .appendToCommandLineRel(builder::append, cellName, pathResolver, useUnixPathSeparator);
      } else {
        arg.appendToCommandLine(builder::append, pathResolver);
      }
    }
    consumer.accept(builder.toString());
  }

  public static CompositeArg of(ImmutableList<Arg> args) {
    return ImmutableCompositeArg.of(args);
  }
}
