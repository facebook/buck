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
import com.facebook.buck.io.filesystem.CopySourceMode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.util.Optional;

/** {@link Action} that copies specified contents to the given output {@link Artifact}s */
public class CopyAction extends AbstractAction {
  @AddToRuleKey private final CopySourceMode mode;

  /**
   * Create an instance of {@link CopyAction}
   *
   * @param actionRegistry the {@link DefaultActionRegistry} to register this action
   * @param input the input {@link Artifact} for this {@link Action}. This is the file to be copied
   * @param output the outputs for this {@link Action}. This is the copy destination
   * @param mode the {@link CopySourceMode} as needed by the filesystem
   */
  public CopyAction(
      ActionRegistry actionRegistry, Artifact input, OutputArtifact output, CopySourceMode mode) {
    super(
        actionRegistry,
        ImmutableSortedSet.of(input),
        ImmutableSortedSet.of(output),
        String.format("copy <%s>", mode));
    this.mode = mode;
  }

  @Override
  public ActionExecutionResult execute(ActionExecutionContext executionContext) {
    ArtifactFilesystem filesystem = executionContext.getArtifactFilesystem();
    Artifact toCopy = Iterables.getOnlyElement(inputs);
    Artifact dest = Iterables.getOnlyElement(outputs).getArtifact();

    try {
      filesystem.copy(toCopy, dest, mode);

      return ActionExecutionResult.success(Optional.empty(), Optional.empty(), ImmutableList.of());
    } catch (IOException e) {
      return ActionExecutionResult.failure(
          Optional.empty(), Optional.empty(), ImmutableList.of(), Optional.of(e));
    }
  }

  @Override
  public boolean isCacheable() {
    return true;
  }
}
