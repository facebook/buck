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

package com.facebook.buck.core.rules.actions.lib;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.ArtifactFilesystem;
import com.facebook.buck.core.artifact.OutputArtifact;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.actions.AbstractAction;
import com.facebook.buck.core.rules.actions.Action;
import com.facebook.buck.core.rules.actions.ActionExecutionContext;
import com.facebook.buck.core.rules.actions.ActionExecutionResult;
import com.facebook.buck.core.rules.actions.ActionRegistry;
import com.facebook.buck.core.rules.actions.DefaultActionRegistry;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgStringifier;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgs;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link Action} that writes specified contents to all of the given output {@link
 * com.facebook.buck.core.artifact.Artifact}s
 */
public class WriteAction extends AbstractAction {
  @AddToRuleKey private final Either<String, CommandLineArgs> contents;
  @AddToRuleKey private final boolean isExecutable;

  /**
   * Create an instance of {@link WriteAction}
   *
   * @param actionRegistry the {@link DefaultActionRegistry} to register this action
   * @param inputs the input {@link Artifact} for this {@link Action}. They can be either outputs of
   *     other {@link Action}s or be source files
   * @param outputs the outputs for this {@link Action}
   * @param contents the contents to write
   * @param isExecutable whether the output is executable
   */
  public WriteAction(
      ActionRegistry actionRegistry,
      ImmutableSortedSet<Artifact> inputs,
      ImmutableSortedSet<OutputArtifact> outputs,
      String contents,
      boolean isExecutable) {
    this(actionRegistry, inputs, outputs, Either.ofLeft(contents), isExecutable);
  }

  /**
   * Create an instance of {@link WriteAction}
   *
   * @param actionRegistry the {@link DefaultActionRegistry} to register this action
   * @param inputs the input {@link Artifact} for this {@link Action}. They can be either outputs of
   *     other {@link Action}s or be source files
   * @param outputs the outputs for this {@link Action}
   * @param contents the contents to write
   * @param isExecutable whether the output is executable
   */
  public WriteAction(
      ActionRegistry actionRegistry,
      ImmutableSortedSet<Artifact> inputs,
      ImmutableSortedSet<OutputArtifact> outputs,
      CommandLineArgs contents,
      boolean isExecutable) {
    this(actionRegistry, inputs, outputs, Either.ofRight(contents), isExecutable);
  }

  private WriteAction(
      ActionRegistry actionRegistry,
      ImmutableSortedSet<Artifact> inputs,
      ImmutableSortedSet<OutputArtifact> outputs,
      Either<String, CommandLineArgs> contents,
      boolean isExecutable) {
    super(actionRegistry, inputs, outputs, "write");
    this.contents = contents;
    this.isExecutable = isExecutable;
  }

  @Override
  public ActionExecutionResult execute(ActionExecutionContext executionContext) {
    ArtifactFilesystem filesystem = executionContext.getArtifactFilesystem();
    String stringContents =
        contents.transform(
            s -> s,
            args ->
                args.getArgsAndFormatStrings()
                    .map(arg -> CommandLineArgStringifier.asString(filesystem, false, arg))
                    .collect(Collectors.joining("\n")));
    for (OutputArtifact output : outputs) {
      try {
        filesystem.writeContentsToPath(stringContents, output.getArtifact());

        if (isExecutable) {
          filesystem.makeExecutable(output.getArtifact());
        }
      } catch (IOException e) {
        return ActionExecutionResult.failure(
            Optional.empty(), Optional.empty(), ImmutableList.of(), Optional.of(e));
      }
    }
    return ActionExecutionResult.success(Optional.empty(), Optional.empty(), ImmutableList.of());
  }

  @Override
  public boolean isCacheable() {
    return true;
  }
}
