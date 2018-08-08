/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.features.go;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

/**
 * A pre-built Go library (ie, typically .a). This can't be a NoopBuildRuleWithDeclaredAndExtraDeps
 * because we need to implement getPathToOutput(). Note that the same library file is used for all
 * build modes, so the library should be a static .rlib compile with STATIC PIC relocation so that
 * its compatible with all other modes. Later we may want to allow per-flavor files.
 */
class PrebuiltGoLibrary extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  @AddToRuleKey(stringify = true)
  private final Path packageName;

  @AddToRuleKey private final SourcePath goLibrary;
  @AddToRuleKey private final ImmutableSortedSet<BuildTarget> exportedDeps;

  public PrebuiltGoLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Path packageName,
      SourcePath goLibrary,
      ImmutableSortedSet<BuildTarget> exportedDeps) {
    super(buildTarget, projectFilesystem, params);

    this.packageName = packageName;
    this.goLibrary = goLibrary;
    this.exportedDeps = exportedDeps;
  }

  ImmutableMap<Path, SourcePath> getGoLinkInput() {
    return ImmutableMap.of(packageName, goLibrary);
  }

  ImmutableSet<BuildTarget> getExportedDeps() {
    return exportedDeps;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ForwardingBuildTargetSourcePath.of(getBuildTarget(), goLibrary);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }
}
