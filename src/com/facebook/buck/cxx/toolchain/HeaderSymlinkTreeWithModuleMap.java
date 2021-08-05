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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.apple.clang.ModuleMap;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public final class HeaderSymlinkTreeWithModuleMap extends HeaderSymlinkTree {

  @AddToRuleKey private final Optional<String> moduleName;
  @AddToRuleKey private final boolean useSubmodules;
  @AddToRuleKey private boolean moduleRequiresCplusplus;

  private HeaderSymlinkTreeWithModuleMap(
      BuildTarget target,
      ProjectFilesystem filesystem,
      Path root,
      ImmutableMap<Path, SourcePath> links,
      Optional<String> moduleName,
      boolean useSubmodules,
      boolean moduleRequiresCplusplus) {
    super(target, filesystem, root, links);
    this.moduleName = moduleName;
    this.useSubmodules = useSubmodules;
    this.moduleRequiresCplusplus = moduleRequiresCplusplus;
  }

  public static HeaderSymlinkTreeWithModuleMap create(
      BuildTarget target,
      ProjectFilesystem filesystem,
      Path root,
      ImmutableMap<Path, SourcePath> links,
      Optional<String> inputModuleName,
      boolean useSubmodules,
      boolean moduleRequiresCplusplus) {
    Optional<String> moduleName =
        inputModuleName.isPresent() ? inputModuleName : getModuleName(links);
    return new HeaderSymlinkTreeWithModuleMap(
        target, filesystem, root, links, moduleName, useSubmodules, moduleRequiresCplusplus);
  }

  public static HeaderSymlinkTreeWithModuleMap create(
      BuildTarget target,
      ProjectFilesystem filesystem,
      Path root,
      ImmutableMap<Path, SourcePath> links,
      Optional<String> inputModuleName,
      boolean useSubmodules) {
    return create(target, filesystem, root, links, inputModuleName, useSubmodules, false);
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    if (moduleName.isPresent()) {
      return ExplicitBuildTargetSourcePath.of(
          getBuildTarget(), moduleMapPath(getProjectFilesystem(), getBuildTarget()));
    } else {
      return super.getSourcePathToOutput();
    }
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableSortedSet<Path> paths = getLinks().keySet();

    ImmutableList.Builder<Step> builder =
        ImmutableList.<Step>builder().addAll(super.getBuildSteps(context, buildableContext));
    moduleName.ifPresent(
        moduleName -> {
          Path expectedSwiftHeaderPath = Paths.get(moduleName, moduleName + "-Swift.h");
          ImmutableSortedSet<Path> pathsWithoutSwiftHeader =
              paths.stream()
                  .filter(path -> !path.equals(expectedSwiftHeaderPath))
                  .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
          Path moduleMapPath = moduleMapPath(getProjectFilesystem(), getBuildTarget()).getPath();

          builder.add(
              new ModuleMapStep(
                  getProjectFilesystem(),
                  moduleMapPath,
                  ModuleMap.create(
                      moduleName,
                      paths.contains(expectedSwiftHeaderPath)
                          ? ModuleMap.SwiftMode.INCLUDE_SWIFT_HEADER
                          : ModuleMap.SwiftMode.NO_SWIFT,
                      pathsWithoutSwiftHeader,
                      useSubmodules,
                      moduleRequiresCplusplus)));
        });
    return builder.build();
  }

  static Optional<String> getModuleName(ImmutableMap<Path, SourcePath> links) {
    if (links.size() > 0) {
      return Optional.of(links.keySet().iterator().next().getName(0).toString());
    } else {
      return Optional.empty();
    }
  }

  static RelPath moduleMapPath(ProjectFilesystem filesystem, BuildTarget target) {
    return BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), target, "%s/module.modulemap");
  }
}
