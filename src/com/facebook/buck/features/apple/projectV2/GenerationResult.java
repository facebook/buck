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

package com.facebook.buck.features.apple.projectV2;

import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.features.apple.common.CopyInXcode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Multimap;
import java.nio.file.Path;
import java.util.Map;

@BuckStyleValue
abstract class GenerationResult {

  public static GenerationResult of(
      Path projectPath,
      boolean projectGenerated,
      Iterable<? extends BuildTarget> requiredBuildTargets,
      Iterable<? extends Path> xcconfigPaths,
      Iterable<? extends CopyInXcode> filesToCopyInXcode,
      Map<? extends BuildTarget, ? extends PBXTarget> buildTargetToGeneratedTargetMap,
      Multimap<? extends PBXProject, ? extends PBXTarget> generatedProjectToPbxTargets) {
    return ImmutableGenerationResult.of(
        projectPath,
        projectGenerated,
        requiredBuildTargets,
        xcconfigPaths,
        filesToCopyInXcode,
        buildTargetToGeneratedTargetMap,
        generatedProjectToPbxTargets);
  }

  public abstract Path getProjectPath();

  public abstract boolean isProjectGenerated();

  public abstract ImmutableSet<BuildTarget> getRequiredBuildTargets();

  public abstract ImmutableSet<Path> getXcconfigPaths();

  public abstract ImmutableList<CopyInXcode> getFilesToCopyInXcode();

  public abstract ImmutableMap<BuildTarget, PBXTarget> getBuildTargetToGeneratedTargetMap();

  public abstract ImmutableSetMultimap<PBXProject, PBXTarget> getGeneratedProjectToPbxTargets();
}
