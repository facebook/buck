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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.actions.AbstractAction;
import com.facebook.buck.core.rules.actions.Action;
import com.facebook.buck.core.rules.actions.ActionExecutionContext;
import com.facebook.buck.core.rules.actions.ActionExecutionResult;
import com.facebook.buck.core.rules.actions.ImmutableActionExecutionFailure;
import com.facebook.buck.core.rules.actions.ImmutableActionExecutionSuccess;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Optional;

/**
 * {@link Action} that writes specified contents to all of the given output {@link
 * com.facebook.buck.core.artifact.Artifact}s
 */
public class WriteAction extends AbstractAction<WriteAction.WriteActionArgs> {

  /**
   * Create an instance of {@link WriteAction}
   *
   * @param owner the {@link BuildTarget} that resulted in the creation of this {@link Action}
   * @param inputs the input {@link Artifact} for this {@link Action}. They can be either outputs of
   *     other {@link Action}s or be source files
   * @param outputs the outputs for this {@link Action}
   * @param params the {@link ActionConstructorParams} for this action.
   */
  public WriteAction(
      BuildTarget owner,
      ImmutableSet<Artifact> inputs,
      ImmutableSet<Artifact> outputs,
      WriteActionArgs params) {
    super(owner, inputs, outputs, params);
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
        filesystem.writeContentsToPath(params.getContent(), output);

        if (params.getIsExecutable()) {
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

  /** Simple arguments required to create a {@link WriteAction} */
  @BuckStyleValue
  public interface WriteActionArgs extends AbstractAction.ActionConstructorParams {

    /** The content that should be written to the file */
    String getContent();

    /** Whether the file should be marked executable */
    boolean getIsExecutable();
  }
}
