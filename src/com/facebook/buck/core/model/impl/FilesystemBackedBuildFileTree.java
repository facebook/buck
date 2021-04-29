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

package com.facebook.buck.core.model.impl;

import com.facebook.buck.core.filesystems.FileName;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildFileTree;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Class to allow looking up parents and children of build files. E.g. for a directory structure
 * that looks like:
 *
 * <pre>
 *   foo/BUCK
 *   foo/bar/baz/BUCK
 *   foo/bar/qux/BUCK
 * </pre>
 *
 * <p>foo/BUCK is the parent of foo/bar/baz/BUCK and foo/bar/qux/BUCK.
 */
public class FilesystemBackedBuildFileTree implements BuildFileTree {
  private final ProjectFilesystem projectFilesystem;
  private final FileName buildFile;

  /**
   * Cache for the base path of a given path. Key is a folder for which base path is wanted and
   * value is a base path. If folder is a package itself (i.e. it does have a build file) then key
   * and value are the same object. All paths are relative to provided filesystem root.
   */
  private final LoadingCache<ForwardRelativePath, Optional<ForwardRelativePath>>
      basePathOfAncestorCache =
          CacheBuilder.newBuilder()
              .weakValues()
              .build(
                  new CacheLoader<ForwardRelativePath, Optional<ForwardRelativePath>>() {
                    @Override
                    public Optional<ForwardRelativePath> load(ForwardRelativePath folderPath)
                        throws Exception {

                      ForwardRelativePath buildFileCandidate = folderPath.resolve(buildFile);

                      // projectFilesystem.isIgnored() is invoked for any folder in a tree
                      // this is not effective, can be optimized by using more efficient tree
                      // matchers
                      // for ignored paths
                      if (projectFilesystem.isFile(buildFileCandidate)
                          && !projectFilesystem.isIgnored(buildFileCandidate)
                          && !isBuckSpecialPath(folderPath)) {
                        return Optional.of(folderPath);
                      }

                      if (folderPath.equals(ForwardRelativePath.EMPTY)) {
                        return Optional.empty();
                      }

                      // traverse up
                      ForwardRelativePath parent = folderPath.getParent();
                      if (parent == null) {
                        parent = ForwardRelativePath.EMPTY;
                      }

                      return basePathOfAncestorCache.get(parent);
                    }
                  });

  public FilesystemBackedBuildFileTree(
      ProjectFilesystem projectFilesystem, FileName buildFileName) {
    this.projectFilesystem = projectFilesystem;
    this.buildFile = buildFileName;
  }

  /**
   * Returns the base path for a given path. The base path is the nearest directory at or above
   * filePath that contains a build file. If no base directory is found, returns an empty path.
   */
  @Override
  public Optional<ForwardRelativePath> getBasePathOfAncestorTarget(ForwardRelativePath filePath) {

    // This will do `stat` which might be expensive. In fact, we almost always know if filePath
    // is a file or folder at caller's site, but the API based on Path is just too generic.
    // To avoid this call we might want to come up with 2 different functions (or cache
    // `projectFilesystem.isFile`) on BuildFileTree interface.
    if (projectFilesystem.isFile(filePath)) {
      filePath = filePath.getParent();
      if (filePath == null) {
        filePath = ForwardRelativePath.EMPTY;
      }
    }

    return basePathOfAncestorCache.getUnchecked(filePath);
  }

  /**
   * @return True if path should be ignored because it is Buck special path, like buck-out or cache
   *     folder, which means it cannot contain build files
   */
  private boolean isBuckSpecialPath(ForwardRelativePath path) {
    Path pathPath = path.toPath(projectFilesystem.getFileSystem());

    RelPath buckOut = projectFilesystem.getBuckPaths().getBuckOut();
    Path buckCache = projectFilesystem.getBuckPaths().getCacheDir();

    return pathPath.startsWith(buckOut.getPath()) || pathPath.startsWith(buckCache);
  }
}
