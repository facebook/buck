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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.android.AndroidPrebuiltAarDescription;
import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.features.project.intellij.model.IjLibraryFactoryResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

class DefaultIjLibraryFactoryResolver implements IjLibraryFactoryResolver {
  private final ProjectFilesystem projectFilesystem;
  private final SourcePathResolverAdapter sourcePathResolver;
  private final Optional<Set<BuildTarget>> requiredBuildTargets;

  DefaultIjLibraryFactoryResolver(
      ProjectFilesystem projectFilesystem,
      IjProjectSourcePathResolver sourcePathResolver,
      Optional<Set<BuildTarget>> requiredBuildTargets) {
    this.projectFilesystem = projectFilesystem;
    this.sourcePathResolver = new SourcePathResolverAdapter(sourcePathResolver);
    this.requiredBuildTargets = requiredBuildTargets;
  }

  @Override
  public Path getPath(SourcePath path) {
    if (path instanceof BuildTargetSourcePath) {
      requiredBuildTargets.ifPresent(
          requiredTargets -> requiredTargets.add(((BuildTargetSourcePath) path).getTarget()));
    }
    return projectFilesystem
        .getRootPath()
        .relativize(sourcePathResolver.getAbsolutePath(path))
        .getPath();
  }

  @Override
  public Optional<SourcePath> getPathIfJavaLibrary(TargetNode<?> targetNode) {
    BuildTarget buildTarget = targetNode.getBuildTarget();
    Optional<SourcePath> toReturn;
    BaseDescription<?> description = targetNode.getDescription();
    if (description instanceof AndroidPrebuiltAarDescription) {
      // From the constructor of UnzipAar.java
      // This is the output path of the classes.jar which buck extracts from the AAR archive
      // so that it can be passed to `javac`. We also use it to provide IntelliJ with a jar
      // so we can reference it as a library.
      Path scratchPath =
          BuildTargetPaths.getScratchPath(
              targetNode.getFilesystem(), buildTarget, "__uber_classes_%s__/classes.jar");
      toReturn =
          Optional.of(
              ExplicitBuildTargetSourcePath.of(
                  buildTarget.withFlavors(AndroidPrebuiltAarDescription.AAR_UNZIP_FLAVOR),
                  scratchPath));
    } else if (IjProjectSourcePathResolver.isJvmLanguageTargetNode(targetNode)) {
      toReturn =
          IjProjectSourcePathResolver.getOutputPathFromJavaTargetNode(
                  targetNode, buildTarget, targetNode.getFilesystem())
              .map(path -> ExplicitBuildTargetSourcePath.of(buildTarget, path));
    } else {
      toReturn = Optional.empty();
    }
    if (toReturn.isPresent()) {
      requiredBuildTargets.ifPresent(requiredTargets -> requiredTargets.add(buildTarget));
    }
    return toReturn;
  }
}
