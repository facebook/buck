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

package com.facebook.buck.step.fs;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.isolatedsteps.common.SymlinkIsolatedStep;
import java.nio.file.Path;

@BuckStyleValue
public abstract class SymlinkFileStep extends DelegateStep<SymlinkIsolatedStep> {

  abstract ProjectFilesystem getFilesystem();

  abstract Path getExistingFile();

  abstract Path getDesiredLink();

  @Override
  protected String getShortNameSuffix() {
    return "symlink_file";
  }

  @Override
  protected SymlinkIsolatedStep createDelegate(StepExecutionContext context) {
    Path existingFilePath = getFilesystem().resolve(getExistingFile());
    Path desiredLinkPath = getFilesystem().resolve(getDesiredLink());

    AbsPath ruleCellRoot = context.getRuleCellRoot();

    return SymlinkIsolatedStep.of(
        ruleCellRoot.relativize(existingFilePath), ruleCellRoot.relativize(desiredLinkPath));
  }

  public static SymlinkFileStep of(
      ProjectFilesystem filesystem, Path existingFile, Path desiredLink) {
    return ImmutableSymlinkFileStep.ofImpl(filesystem, existingFile, desiredLink);
  }
}
