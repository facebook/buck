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

package com.facebook.buck.core.rules.actions.lib.args;

import com.google.common.collect.ImmutableList;
import java.util.stream.Stream;

/**
 * Container class for lists of {@link CommandLineArgs}. This is useful when merging args that were
 * passed in as providers, as the backing objects are immutable, and thus can be iterated over
 * without copying them.
 */
class AggregateCommandLineArgs implements CommandLineArgs {

  private final ImmutableList<CommandLineArgs> args;

  AggregateCommandLineArgs(ImmutableList<CommandLineArgs> args) {
    this.args = args;
  }

  @Override
  public int getEstimatedArgsCount() {
    return args.stream().map(CommandLineArgs::getEstimatedArgsCount).reduce(0, Integer::sum);
  }

  @Override
  public Stream<Object> getArgs() {
    return args.stream().flatMap(CommandLineArgs::getArgs);
  }
}
