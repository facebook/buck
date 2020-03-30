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

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.OutputArtifact;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Container class for lists of {@link CommandLineArgs}. This is useful when merging args that were
 * passed in as providers, as the backing objects are immutable, and thus can be iterated over
 * without copying them.
 */
class AggregateCommandLineArgs implements CommandLineArgs {
  @AddToRuleKey private final ImmutableList<CommandLineArgs> args;

  AggregateCommandLineArgs(ImmutableList<CommandLineArgs> args) {
    this.args = args;
  }

  @Override
  public int getEstimatedArgsCount() {
    return args.stream().map(CommandLineArgs::getEstimatedArgsCount).reduce(0, Integer::sum);
  }

  @Override
  public void visitInputsAndOutputs(Consumer<Artifact> inputs, Consumer<OutputArtifact> outputs) {
    args.forEach(arg -> arg.visitInputsAndOutputs(inputs, outputs));
  }

  @Override
  public ImmutableSortedMap<String, String> getEnvironmentVariables() {
    ImmutableSortedMap.Builder<String, String> builder = ImmutableSortedMap.naturalOrder();
    try {
      for (CommandLineArgs arg : args) {
        builder.putAll(arg.getEnvironmentVariables());
      }
      return builder.build();
    } catch (IllegalArgumentException e) {
      // Thrown if two arguments have the same keys
      // TODO(pjameson): Decide if we want to have a way to override instead
      throw new HumanReadableException(
          e, "Error getting commandline arguments: %s", e.getMessage());
    }
  }

  @Override
  public Stream<ArgAndFormatString> getArgsAndFormatStrings() {
    return args.stream().flatMap(CommandLineArgs::getArgsAndFormatStrings);
  }
}
