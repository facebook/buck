/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.android.AndroidPrebuiltAar;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.features.project.intellij.model.IjLibraryFactoryResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

class DefaultIjLibraryFactoryResolver implements IjLibraryFactoryResolver {
  private final ProjectFilesystem projectFilesystem;
  private final BuildRuleResolver buildRuleResolver;
  private final Set<BuildTarget> requiredBuildTargets;

  DefaultIjLibraryFactoryResolver(
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver buildRuleResolver,
      Set<BuildTarget> requiredBuildTargets) {
    this.projectFilesystem = projectFilesystem;
    this.buildRuleResolver = buildRuleResolver;
    this.requiredBuildTargets = requiredBuildTargets;
  }

  @Override
  public Path getPath(SourcePath path) {
    Optional<BuildRule> rule = buildRuleResolver.getRule(path);
    if (rule.isPresent()) {
      requiredBuildTargets.add(rule.get().getBuildTarget());
    }
    return projectFilesystem
        .getRootPath()
        .relativize(buildRuleResolver.getSourcePathResolver().getAbsolutePath(path));
  }

  @Override
  public Optional<SourcePath> getPathIfJavaLibrary(TargetNode<?> targetNode) {
    BuildRule rule = buildRuleResolver.getRule(targetNode.getBuildTarget());
    if (!(rule instanceof JavaLibrary)) {
      return Optional.empty();
    }
    if (rule instanceof AndroidPrebuiltAar) {
      AndroidPrebuiltAar aarRule = (AndroidPrebuiltAar) rule;
      return Optional.ofNullable(aarRule.getBinaryJar());
    }
    requiredBuildTargets.add(rule.getBuildTarget());
    return Optional.ofNullable(rule.getSourcePathToOutput());
  }
}
