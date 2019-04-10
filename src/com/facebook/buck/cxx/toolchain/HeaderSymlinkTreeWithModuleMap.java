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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.apple.clang.ModuleMap;
import com.facebook.buck.apple.clang.UmbrellaHeader;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public final class HeaderSymlinkTreeWithModuleMap extends HeaderSymlinkTree {

  @AddToRuleKey private final Optional<String> moduleName;

  private HeaderSymlinkTreeWithModuleMap(
      BuildTarget target,
      ProjectFilesystem filesystem,
      Path root,
      ImmutableMap<Path, SourcePath> links,
      SourcePathRuleFinder ruleFinder,
      Optional<String> moduleName) {
    super(target, filesystem, root, links, ruleFinder);
    this.moduleName = moduleName;
  }

  public static HeaderSymlinkTreeWithModuleMap create(
      BuildTarget target,
      ProjectFilesystem filesystem,
      Path root,
      ImmutableMap<Path, SourcePath> links,
      SourcePathRuleFinder ruleFinder) {
    Optional<String> moduleName = getModuleName(links);
    return new HeaderSymlinkTreeWithModuleMap(
        target, filesystem, root, links, ruleFinder, moduleName);
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    if (moduleName.isPresent()) {
      return ExplicitBuildTargetSourcePath.of(
          getBuildTarget(),
          moduleMapPath(getProjectFilesystem(), getBuildTarget(), moduleName.get()));
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
          builder.add(
              new ModuleMapStep(
                  getProjectFilesystem(),
                  moduleMapPath(getProjectFilesystem(), getBuildTarget(), moduleName),
                  new ModuleMap(
                      moduleName,
                      containsSwiftHeader(paths, moduleName)
                          ? ModuleMap.SwiftMode.INCLUDE_SWIFT_HEADER
                          : ModuleMap.SwiftMode.NO_SWIFT)));

          Path umbrellaHeaderPath = Paths.get(moduleName, moduleName + ".h");
          if (!paths.contains(umbrellaHeaderPath)) {
            builder.add(
                new WriteFileStep(
                    getProjectFilesystem(),
                    new UmbrellaHeader(
                            moduleName,
                            getLinks().keySet().stream()
                                .map(x -> x.getFileName().toString())
                                .collect(ImmutableList.toImmutableList()))
                        .render(),
                    BuildTargetPaths.getGenPath(
                        getProjectFilesystem(), getBuildTarget(), "%s/" + umbrellaHeaderPath),
                    false));
          }
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

  static Path moduleMapPath(ProjectFilesystem filesystem, BuildTarget target, String moduleName) {
    return BuildTargetPaths.getGenPath(
        filesystem, target, "%s/" + moduleName + "/module.modulemap");
  }

  private static boolean containsSwiftHeader(ImmutableSortedSet<Path> paths, String moduleName) {
    return paths.contains(Paths.get(moduleName, moduleName + "-Swift.h"));
  }
}
