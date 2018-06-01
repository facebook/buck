/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.rules.common;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.Optional;

/**
 * InstallTrigger is effectively a SourcePath whose content changes on every install. It is only
 * available during `buck install`, not `buck build` or `buck test`, etc. This is intended to be
 * used as an input to BuildRules that read device state so that they can be run on every build.
 */
public class InstallTrigger implements AddsToRuleKey {
  @AddToRuleKey private final SourcePath path;

  private final ProjectFilesystem filesystem;

  public InstallTrigger(ProjectFilesystem filesystem) {
    this.filesystem = filesystem;
    this.path = PathSourcePath.of(filesystem, getTriggerPath(filesystem));
  }

  public static Path getTriggerPath(ProjectFilesystem filesystem) {
    return filesystem.getBuckPaths().getScratchDir().resolve("install.trigger");
  }

  public void verify(ExecutionContext context) {
    Optional<String> uuid = filesystem.readFirstLine(getTriggerPath(filesystem));
    Preconditions.checkState(uuid.isPresent());
    Preconditions.checkState(uuid.get().equals(context.getBuildId().toString()));
  }
}
