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
package com.facebook.buck.features.project.intellij;

import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.RobolectricTestDescription;
import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.impl.AbstractSourcePathResolver;
import com.facebook.buck.features.filegroup.FileGroupDescriptionArg;
import com.facebook.buck.features.filegroup.FilegroupDescription;
import com.facebook.buck.file.RemoteFileDescription;
import com.facebook.buck.file.RemoteFileDescriptionArg;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.groovy.GroovyLibraryDescription;
import com.facebook.buck.jvm.groovy.GroovyTestDescription;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.kotlin.KotlinLibraryDescription;
import com.facebook.buck.jvm.kotlin.KotlinTestDescription;
import com.facebook.buck.jvm.scala.ScalaLibraryDescription;
import com.facebook.buck.jvm.scala.ScalaTestDescription;
import com.facebook.buck.shell.ExportFileDescription;
import com.facebook.buck.shell.ExportFileDescriptionArg;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A SourcePathResolver implementation that uses only information found in the target graph when
 * converting a BuildTargetSourcePath to an outputPath. This allows the IjProject code to rely only
 * on the TargetGraph when constructing a project.
 */
public class IjProjectSourcePathResolver extends AbstractSourcePathResolver {

  private final TargetGraph targetGraph;

  public IjProjectSourcePathResolver(TargetGraph targetGraph) {
    this.targetGraph = targetGraph;
  }

  /**
   * This function mimics the behavior of `BuildRule#getSourcePathToOutput()` but does so without
   * access to the BuildRule implementations or the ActionGraph. This is important since it allows
   * us to resolve SourcePaths from the TargetGraph alone.
   *
   * @return the output path for the given targetNode or Optional.empty() if we don't know how to
   *     resolve the given TargetNode type to an output path.
   */
  private Optional<Path> getOutputPathForTargetNode(TargetNode<?> targetNode) {
    BaseDescription<?> description = targetNode.getDescription();
    BuildTarget buildTarget = targetNode.getBuildTarget();
    ProjectFilesystem filesystem = targetNode.getFilesystem();

    if (description instanceof ExportFileDescription) {
      return getOutputPathForExportFile(
          (ExportFileDescriptionArg) targetNode.getConstructorArg(), buildTarget, filesystem);
    } else if (description instanceof RemoteFileDescription) {
      return getOutputPathForRemoteFile(
          (RemoteFileDescriptionArg) targetNode.getConstructorArg(), buildTarget, filesystem);
    } else if (description instanceof FilegroupDescription) {
      return getOutputPathForFilegroup(
          (FileGroupDescriptionArg) targetNode.getConstructorArg(), buildTarget, filesystem);
    } else {
      // This SourcePathResolver does not attempt to exhaustively list all possible rule
      // descriptions,
      // instead opting to only implement those relevant for IjProject
      return Optional.empty();
    }
  }

  /**
   * Resolve the default output path for the given targetSourcePath, returning a sourcepath pointing
   * to the output.
   */
  @Override
  protected SourcePath resolveDefaultBuildTargetSourcePath(
      DefaultBuildTargetSourcePath targetSourcePath) {
    BuildTarget target = targetSourcePath.getTarget();
    TargetNode<?> targetNode = targetGraph.get(target);
    Optional<Path> outputPath = getOutputPathForTargetNode(targetNode);
    return PathSourcePath.of(
        targetNode.getFilesystem(),
        outputPath.orElseThrow(
            () -> new HumanReadableException("No known output for: %s", target)));
  }

  /**
   * Source path names are only used when constructing the ActionGraph, so we don't need to support
   * them here.
   */
  @Override
  public String getSourcePathName(BuildTarget target, SourcePath sourcePath) {
    throw new UnsupportedOperationException();
  }

  /** @return the filesystem instance that corresponds to the given BuildTargetSourcePath */
  @Override
  protected ProjectFilesystem getBuildTargetSourcePathFilesystem(BuildTargetSourcePath sourcePath) {
    return targetGraph.get(sourcePath.getTarget()).getFilesystem();
  }

  /** @return The BuildTarget portion of the sourcePath if present */
  public Optional<BuildTarget> getBuildTarget(SourcePath sourcePath) {
    if (sourcePath instanceof BuildTargetSourcePath) {
      return Optional.of(((BuildTargetSourcePath) sourcePath).getTarget());
    } else {
      return Optional.empty();
    }
  }

  /** @return true if the given target node describes a rule targeting the JVM */
  public static boolean isJvmLanguageTargetNode(TargetNode<?> targetNode) {
    BaseDescription<?> description = targetNode.getDescription();
    return description instanceof JavaLibraryDescription
        || description instanceof AndroidLibraryDescription
        || description instanceof ScalaLibraryDescription
        || description instanceof GroovyLibraryDescription
        || description instanceof KotlinLibraryDescription;
  }

  /** @return true if the given target node describes a junit/ngtest rule targeting the JVM */
  public static boolean isJvmTestTargetNode(TargetNode<?> targetNode) {
    BaseDescription<?> description = targetNode.getDescription();
    return description instanceof JavaTestDescription
        || description instanceof RobolectricTestDescription
        || description instanceof ScalaTestDescription
        || description instanceof GroovyTestDescription
        || description instanceof KotlinTestDescription;
  }

  /** Calculate the output path for a Filegroup rule */
  private Optional<Path> getOutputPathForFilegroup(
      FileGroupDescriptionArg arg, BuildTarget buildTarget, ProjectFilesystem filesystem) {
    // This matches the implementation in Filegroup which uses the name of the rule as the output
    // name for the exported files
    String filename = arg.getName();
    return Optional.of(BuildPaths.getGenDir(filesystem, buildTarget).resolve(filename));
  }

  /** Calculate the output path for a RemoteFile rule */
  private Optional<Path> getOutputPathForRemoteFile(
      RemoteFileDescriptionArg arg, BuildTarget buildTarget, ProjectFilesystem filesystem) {
    // This matches the implementation in RemoteFileDescription for the output filename
    // calculation
    String filename = arg.getOut().orElse(buildTarget.getShortNameAndFlavorPostfix());
    // This matches the output path calculation in RemoteFile's constructor
    return Optional.of(BuildTargetPaths.getGenPath(filesystem, buildTarget, "%s/" + filename));
  }

  /** Calculate the output path for an ExportFile rule */
  private Optional<Path> getOutputPathForExportFile(
      ExportFileDescriptionArg arg, BuildTarget buildTarget, ProjectFilesystem filesystem) {
    // This matches the implementation in ExportFileDescription
    // If the mode is REFERENCE we need to return the relative path to the real underlying file
    if (arg.getMode().map(mode -> mode == ExportFileDescription.Mode.REFERENCE).orElse(false)) {
      return arg.getSrc().map(this::getRelativePath);
    }
    // Otherwise, we resolve the generated path for the COPY
    String name = arg.getOut().orElse(buildTarget.getShortNameAndFlavorPostfix());
    return Optional.of(BuildTargetPaths.getGenPath(filesystem, buildTarget, "%s").resolve(name));
  }
}
