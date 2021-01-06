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
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * A container {@link CommandLineArgs} for holding and transforming an entire list of command line
 * arguments.
 *
 * <p>This class is more efficient when a user provides an entire list of e.g. strings, integers,
 * etc, as we do not have to create an entire new list of {@link CommandLineArgs}. Returned streams
 * can also be more efficient
 */
class ListCommandLineArgs implements CommandLineArgs {
  @AddToRuleKey private final ImmutableList<Object> objects;
  @AddToRuleKey private final String formatString;

  /**
   * Create an instance of {@link ListCommandLineArgs}
   *
   * @param objects a list of command line arguments. These must have been validated by {@link
   *     CommandLineArgsFactory}
   */
  ListCommandLineArgs(ImmutableList<Object> objects, String formatString) {
    this.objects = objects;
    this.formatString = formatString;
  }

  @Override
  public ImmutableSortedMap<String, String> getEnvironmentVariables() {
    return ImmutableSortedMap.of();
  }

  @Override
  public Stream<ArgAndFormatString> getArgsAndFormatStrings() {
    return objects.stream().map(o -> ImmutableArgAndFormatString.of(o, formatString));
  }

  @Override
  public int getEstimatedArgsCount() {
    return objects.size();
  }

  @Override
  public void visitInputsAndOutputs(Consumer<Artifact> inputs, Consumer<OutputArtifact> outputs) {
    objects.forEach(
        arg -> {
          if (arg instanceof Artifact) {
            inputs.accept((Artifact) arg);
          } else if (arg instanceof OutputArtifact) {
            outputs.accept((OutputArtifact) arg);
          }
        });
  }
}
