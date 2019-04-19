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

package com.facebook.buck.parser.manifest;

import com.facebook.buck.core.graph.transformation.GraphEngineCache;
import com.facebook.buck.event.FileHashCacheEvent;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystemView;
import com.facebook.buck.io.watchman.WatchmanOverflowEvent;
import com.facebook.buck.io.watchman.WatchmanPathEvent;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.google.common.eventbus.Subscribe;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/** Stores {@link BuildFileManifest} for each parsed build file */
public class BuildFileManifestCache
    implements GraphEngineCache<BuildPackagePathToBuildFileManifestKey, BuildFileManifest> {

  /**
   * Main cache storage. Key is a path to a folder that is a package root (i.e. a folder that has
   * build file), relative to a cell path (rootPath, not superRootPath). Value is parsed {@link
   * BuildFileManifest}.
   */
  private ConcurrentHashMap<Path, BuildFileManifest> cache = new ConcurrentHashMap<>();

  private final Invalidator invalidator;

  private BuildFileManifestCache(
      Path superRootPath, Path rootPath, Path buildFileName, ProjectFilesystemView fileSystemView) {
    invalidator = new Invalidator(this, superRootPath, rootPath, buildFileName, fileSystemView);
  }

  /**
   * Create a new instance of {@link BuildFileManifestCache}
   *
   * @param superRootPath Absolute path to the cell root folder which contains all other cells. All
   *     files and includes should be descendants of this path.
   * @param rootPath Absolute path to the root folder for which files and subfolders are cached,
   *     this is usually the root path of the cell. It may be the same path as {@code superRootPath}
   *     or a subfolder of it.
   * @param buildFileName File name of the build file (for example, BUCK) in a form of a {@link
   *     Path}
   * @param fileSystemView {@link ProjectFilesystemView} that is used to physically access files,
   *     used for invalidations
   */
  public static BuildFileManifestCache of(
      Path superRootPath, Path rootPath, Path buildFileName, ProjectFilesystemView fileSystemView) {
    return new BuildFileManifestCache(superRootPath, rootPath, buildFileName, fileSystemView);
  }

  @Override
  public Optional<BuildFileManifest> get(BuildPackagePathToBuildFileManifestKey key) {
    return Optional.ofNullable(cache.get(key.getPath()));
  }

  @Override
  public void put(BuildPackagePathToBuildFileManifestKey key, BuildFileManifest buildFileManifest) {
    @Nullable BuildFileManifest prevManifest = cache.put(key.getPath(), buildFileManifest);

    if (buildFileManifest.equals(prevManifest)) {
      // no need to recompute invalidation index
      return;
    }

    if (prevManifest != null) {
      // some manifest was evicted, remove it from index
      invalidator.removeFromIndex(key.getPath(), prevManifest);
    }

    invalidator.addToIndex(key.getPath(), buildFileManifest);
  }

  /** @return class that listens to watchman events and invalidates internal cache state */
  public Invalidator getInvalidator() {
    return invalidator;
  }

  /**
   * Subscribes to watchman event and invalidates internal state of a provided {@link
   * BuildFileManifestCache}
   */
  public static class Invalidator {
    private final BuildFileManifestCache buildFileManifestCache;
    private final Path superRootPath;
    private final Path rootPath;
    private final Path rootToSuperRootRelativePath;
    private final Path buildFileName;
    private final ProjectFilesystemView fileSystemView;

    /**
     * Index of the dependent files to packages that use those dependencies. The key is a path to a
     * dependent file (usually, .bzl file) relative to the root cell (because includes may come from
     * different cells). The value is a set of relative paths to folders uniquely identifying
     * package root; those paths are actually keys in {@link BuildFileManifestCache#cache}
     */
    private ConcurrentHashMap<Path, Set<Path>> dependentIndex = new ConcurrentHashMap<>();

    /**
     * Cache file system access. The key is the path we checked to be package root, the value is
     * Boolean saying whether that path is package root or not.
     */
    private ConcurrentHashMap<Path, Boolean> invalidationPackageCache = new ConcurrentHashMap<>();

    private Invalidator(
        BuildFileManifestCache buildFileManifestCache,
        Path superRootPath,
        Path rootPath,
        Path buildFileName,
        ProjectFilesystemView fileSystemView) {
      this.buildFileManifestCache = buildFileManifestCache;
      this.superRootPath = superRootPath;
      this.rootPath = rootPath;
      this.buildFileName = buildFileName;
      this.fileSystemView = fileSystemView;

      rootToSuperRootRelativePath = superRootPath.relativize(rootPath);
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onInvalidationStart(FileHashCacheEvent.InvalidationStarted event) {
      // reinstantiate just in case
      invalidationPackageCache = new ConcurrentHashMap<>();
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onInvalidationFinish(FileHashCacheEvent.InvalidationFinished event) {
      // drop invalidation cache to free some memory
      invalidationPackageCache = new ConcurrentHashMap<>();
    }

    /** Invoked asynchronously by event bus when file system change is detected with Watchman */
    @Subscribe
    public void onFileSystemChange(WatchmanPathEvent event) {

      // If dependency file is modified or deleted, invalidate packages that depend on it
      // Dependent files may come from different cells
      if (event.getKind() == WatchmanPathEvent.Kind.MODIFY
          || event.getKind() == WatchmanPathEvent.Kind.DELETE) {
        Path eventPath = event.getPath();

        // Convert any path to be relative to super root, because that's how we store dependencies
        Path relativeToSuperRootPath =
            eventPath.isAbsolute()
                ? superRootPath.relativize(eventPath)
                : rootToSuperRootRelativePath.resolve(eventPath);
        invalidateDependencies(relativeToSuperRootPath);

        // We do not stop here and can also potentially invalidate the package that has, but does
        // not really references, those dependency files. We do this because those .bzl files
        // can also be somehow used as sources in containing package too. Should we not?
      }

      // other changes are only applicable to current cell
      if (!rootPath.equals(event.getCellPath())) {
        return;
      }

      // Build file was altered
      if (event.getPath().endsWith(buildFileName)) {
        Path packagePath = MorePaths.getParentOrEmpty(event.getPath());
        switch (event.getKind()) {
          case MODIFY:
            // If build file is modified, just invalidate containing package
            invalidatePackage(packagePath);
            break;
          case CREATE:
          case DELETE:
            // If build file is created or deleted, invalidate current and parent packages
            // Potentially there may be the case when we do not need to invalidate parent
            // package (if new build file is created in new directory), but we ignore it for now
            invalidatePackage(packagePath);
            invalidateContainingPackage(MorePaths.getParentOrEmpty(packagePath));
            break;
          default:
            throw new UnsupportedOperationException(event.getKind().getClass().getName());
        }
        return;
      }

      // some other regular file was altered
      switch (event.getKind()) {
        case MODIFY:
          // modifications to regular files do not affect packages
          break;
        case CREATE:
        case DELETE:
          Path packagePath = MorePaths.getParentOrEmpty(event.getPath());
          // if regular file is created or deleted, invalidate containing package
          // TODO: consider package boundary violations
          invalidateContainingPackage(packagePath);
          break;
        default:
          throw new UnsupportedOperationException(event.getKind().getClass().getName());
      }
    }

    /**
     * Invalidate package with is rooted at provided path
     *
     * @param path Relative path to the root of the package, i.e. path to a folder where build file
     *     is
     * @return True if package was actually removed from cache, false otherwise
     */
    private boolean invalidatePackage(Path path) {
      BuildFileManifest manifest = buildFileManifestCache.cache.remove(path);
      if (manifest == null) {
        return false;
      }
      removeFromIndex(path, manifest);
      return true;
    }

    /**
     * Invalidate closest package to a provided path by traversing that path up the folder tree
     *
     * @param path Relative path to a folder that may be a package root
     * @return True if package was actually removed from cache, false otherwise
     */
    private boolean invalidateContainingPackage(Path path) {
      // Finding a parent package is not trivial.
      // We traverse up the directory tree. If package is found in the cache, we invalidate it and
      // call it done. If not found, it means that either a package was not loaded or parent folder
      // is not a package root, so we recourse to file system and look for the build file to
      // determine that
      while (true) {
        if (invalidatePackage(path)) {
          // easy case - package was loaded to cache so we know it is there
          return true;
        }

        // Use cache to only ask filesystem once per each folder. This makes sense assuming coming
        // changes usually come from same or close paths.
        boolean isPackageRoot =
            invalidationPackageCache.computeIfAbsent(
                path, p -> fileSystemView.isFile(p.resolve(buildFileName)));

        if (isPackageRoot) {
          // ok this folder is actually a root of the package which was never loaded to a cache. We
          // do not need to invalidate anything up from here. Just return.
          return false;
        }

        if (MorePaths.isEmpty(path)) {
          // Empty path means root. Nothing to traverse beyond that.
          return false;
        }

        path = MorePaths.getParentOrEmpty(path);
      }
    }

    /** For invalidated manifests, update corresponding index */
    private void removeFromIndex(Path key, BuildFileManifest manifest) {
      for (String include : manifest.getIncludes()) {
        Path dependent = relativizeIfNeeded(include);

        dependentIndex.compute(
            dependent,
            (path, setOfManifestKeys) -> {
              if (setOfManifestKeys == null) {
                return null;
              }
              setOfManifestKeys.remove(key);
              return setOfManifestKeys.isEmpty() ? null : setOfManifestKeys;
            });
      }
    }

    /** Calculate dependency index for this manifest */
    private void addToIndex(Path key, BuildFileManifest manifest) {
      for (String include : manifest.getIncludes()) {
        Path dependent = relativizeIfNeeded(include);

        if (dependent.endsWith(buildFileName)) {
          // includes may include build file itself; we do not want it here as it is processed
          // separately
          continue;
        }

        dependentIndex.compute(
            dependent,
            (path, setOfManifestKeys) -> {
              if (setOfManifestKeys == null) {
                setOfManifestKeys = new HashSet<>();
              }
              setOfManifestKeys.add(key);
              return setOfManifestKeys;
            });
      }
    }

    private Path relativizeIfNeeded(String include) {
      Path dependent = Paths.get(include);
      // Support both absolute and relative paths. If relative, think of it relative to root
      // cell. At this point parser returns absolute path strings, but we want to switch it to
      // return relative Path objects one day
      if (dependent.isAbsolute()) {
        // now the path is relative to root cell, not local cell because includes can come from
        // different cells
        dependent = superRootPath.relativize(dependent);
      }
      return dependent;
    }

    /**
     * Invalidate (remove from cache) all manifests that depend on provided file content
     *
     * @param path Path to a dependent file, usually .bzl file, relative to a root cell
     */
    private void invalidateDependencies(Path path) {
      @Nullable Set<Path> affectedPackages = dependentIndex.remove(path);
      if (affectedPackages != null) {
        for (Path key : affectedPackages) {
          buildFileManifestCache.cache.remove(key);
        }
      }
    }

    /**
     * Invoked asynchronously by event bus when Watchman detects too many files changed or unable to
     * detect changes, this should drop the cache
     */
    @Subscribe
    @SuppressWarnings("unused")
    public void onFileSystemChange(WatchmanOverflowEvent event) {
      buildFileManifestCache.cache = new ConcurrentHashMap<>();
      dependentIndex = new ConcurrentHashMap<>();
    }
  }
}
