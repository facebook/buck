/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.rules.actions.lib;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.ArtifactFilesystem;
import com.facebook.buck.core.rules.actions.AbstractAction;
import com.facebook.buck.core.rules.actions.Action;
import com.facebook.buck.core.rules.actions.ActionExecutionContext;
import com.facebook.buck.core.rules.actions.ActionExecutionResult;
import com.facebook.buck.core.rules.actions.ActionRegistry;
import com.facebook.buck.core.rules.actions.DefaultActionRegistry;
import com.facebook.buck.core.rules.actions.ImmutableActionExecutionFailure;
import com.facebook.buck.core.rules.actions.ImmutableActionExecutionSuccess;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Optional;

/**
 * {@link Action} that writes specified contents to all of the given output {@link
 * com.facebook.buck.core.artifact.Artifact}s
 */
public class WriteAction extends AbstractAction {

  private final String contents;
  private final boolean isExecutable;

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
      ImmutableSet<Artifact> inputs,
      ImmutableSet<Artifact> outputs,
      String contents,
      boolean isExecutable) {
    super(actionRegistry, inputs, outputs);
    this.contents = contents;
    this.isExecutable = isExecutable;
  }

  @Override
  public String getShortName() {
    return "write";
  }

  @Override
  public ActionExecutionResult execute(ActionExecutionContext executionContext) {
    ArtifactFilesystem filesystem = executionContext.getArtifactFilesystem();
    for (Artifact output : outputs) {
      try {
        filesystem.writeContentsToPath(contents, output);

        if (isExecutable) {
          filesystem.makeExecutable(output);
        }
      } catch (IOException e) {
        return ImmutableActionExecutionFailure.of(
            Optional.empty(), Optional.empty(), Optional.of(e));
      }
    }
    return ImmutableActionExecutionSuccess.of(Optional.empty(), Optional.empty());
  }

  @Override
  public boolean isCacheable() {
    return true;
  }
}
