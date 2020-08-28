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

package com.facebook.buck.external.main;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.external.model.ExternalAction;
import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MkdirIsolatedStep;
import com.google.common.collect.ImmutableList;

/**
 * {@link ExternalAction} for testing purposes. Expects one arg from the {@link BuildableCommand}: a
 * relative path name to a directory to be created by this {@link ExternalAction}.
 */
public class FakeMkdirExternalAction implements ExternalAction {

  @Override
  public ImmutableList<IsolatedStep> getSteps(BuildableCommand buildableCommand) {
    String path = buildableCommand.getArgs(0);
    return ImmutableList.of(MkdirIsolatedStep.of(RelPath.get(path)));
  }
}
