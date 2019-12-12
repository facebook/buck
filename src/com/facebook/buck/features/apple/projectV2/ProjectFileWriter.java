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

import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXGroup;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/** A class for writing files in SourcePath or Path objects to a PBXProject. */
public class ProjectFileWriter {
  private final PBXProject project;
  private final PathRelativizer pathRelativizer;
  private final Function<SourcePath, Path> sourcePathResolver;

  public ProjectFileWriter(
      PBXProject project,
      PathRelativizer pathRelativizer,
      Function<SourcePath, Path> sourcePathResolver) {
    this.project = project;
    this.pathRelativizer = pathRelativizer;
    this.sourcePathResolver = sourcePathResolver;
  }

  /**
   * Helper class for returning metadata about a created PBXFileReference Includes a reference to
   * the PBXFileReference and the Source Tree Path We probably won't need this long term as we kill
   * off Xcode build phases but for now, let's just use this since it's named and structured
   */
  public static class Result {
    private final PBXFileReference fileReference;
    private final SourceTreePath sourceTreePath;

    public Result(PBXFileReference fileReference, SourceTreePath sourceTreePath) {
      this.fileReference = fileReference;
      this.sourceTreePath = sourceTreePath;
    }

    public PBXFileReference getFileReference() {
      return fileReference;
    }

    public SourceTreePath getSourceTreePath() {
      return sourceTreePath;
    }
  }

  /**
   * Writes a source path to a PBXFileReference in the input project. This will create the relative
   * directory structure based on the path relativizer (cell root).
   *
   * <p>Thus, if a file is absolutely located at: /Users/me/dev/MyProject/Header.h And the cell is:
   * /Users/me/dev/ Then this will create a path in the PBXProject mainGroup as: /MyProject/Header.h
   */
  public ProjectFileWriter.Result writeSourcePath(
      SourcePath sourcePath, Optional<Path> packagePath) {
    Path path = sourcePathResolver.apply(sourcePath);

    // BuildTargetSourcePath indicates it's not a file but a build target (meaning it is generated)
    // As a result, we want to move these from `buck-out` into a __generated__ workspace
    // in order to make it easier for engineers to see which source files are generated.
    if (sourcePath instanceof BuildTargetSourcePath && packagePath.isPresent()) {
      Path fullPackagePath = packagePath.get();
      String packageName = "-" + new File(fullPackagePath.toString()).getName();
      path = fullPackagePath.resolve("GENERATED" + packageName).resolve(path.getFileName());
    }

    SourceTreePath sourceTreePath =
        new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            pathRelativizer.outputPathToSourcePath(sourcePath),
            Optional.empty());
    return writeSourceTreePathAtFullPathToProject(path, sourceTreePath);
  }

  /** Writes a file at a path to a PBXFileReference in the input project. */
  public ProjectFileWriter.Result writeFilePath(Path path, Optional<String> type) {
    SourceTreePath sourceTreePath =
        new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            pathRelativizer.outputDirToRootRelative(path),
            type);
    return writeSourceTreePathAtFullPathToProject(path, sourceTreePath);
  }

  private ProjectFileWriter.Result writeSourceTreePathAtFullPathToProject(
      Path path, SourceTreePath sourceTreePath) {
    PBXGroup filePathGroup;
    // This check exists for files located in the root (e.g. ./foo.m instead of ./MyLibrary/foo.m)
    if (path.getParent() != null) {
      ImmutableList<String> filePathComponentList =
          RichStream.from(path.getParent()).map(Object::toString).toImmutableList();
      filePathGroup =
          project.getMainGroup().getOrCreateDescendantGroupByPath(filePathComponentList);
    } else {
      filePathGroup = project.getMainGroup();
    }

    PBXFileReference fileReference =
        filePathGroup.getOrCreateFileReferenceBySourceTreePath(sourceTreePath);

    return new Result(fileReference, sourceTreePath);
  }
}
