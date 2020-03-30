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

package com.facebook.buck.rules.macros;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.HasSupplementaryOutputs;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.rules.args.Arg;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Represents an {@link Arg} that is to be generated from a string macros by replacing references to
 * the outputs of a rule with its Path.
 */
abstract class AbstractOutputArg implements Arg {
  @AddToRuleKey private final String name;

  private final BuildRuleResolver resolver;
  private final BuildTarget target;

  AbstractOutputArg(String input, BuildRuleResolver resolver, BuildTarget target) {
    this.resolver = resolver;
    this.target = target;
    this.name = input;
  }

  /**
   * @param path the {@link SourcePath} of the rule's output
   * @return the {@link Path} to be used for generating the {@link Arg}
   */
  abstract Path sourcePathToArgPath(SourcePath path, SourcePathResolverAdapter resolver);

  @Override
  public void appendToCommandLine(
      Consumer<String> consumer, SourcePathResolverAdapter pathResolver) {
    // Ideally, we'd support some way to query the `HasSupplementalOutputs` interface via a
    // `SourcePathResolverAdapter` so we wouldn't need to capture the `BuildRuleResolver`.
    BuildRule rule =
        resolver
            .getRuleOptional(target)
            .orElseThrow(
                () ->
                    new AssertionError(
                        "Expected build target to resolve to a build rule: " + target));
    if (rule instanceof HasSupplementaryOutputs) {
      SourcePath output = ((HasSupplementaryOutputs) rule).getSourcePathToSupplementaryOutput(name);
      if (output != null) {
        // Note: As this is only used by the declaring rule, it can just use a relative path
        // here as it will necessarily be expanded in the same cell.
        consumer.accept(sourcePathToArgPath(output, pathResolver).toString());
        return;
      }
    }
    throw new HumanReadableException(
        String.format("%s: rule does not have an output with the name: %s", target, name));
  }
}
