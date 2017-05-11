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

package com.facebook.buck.ide.intellij;

import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.ide.intellij.lang.java.JavaPackagePathCache;
import com.facebook.buck.ide.intellij.model.folders.ExcludeFolder;
import com.facebook.buck.ide.intellij.model.folders.IjFolder;
import com.facebook.buck.ide.intellij.model.folders.SelfMergingOnlyFolder;
import com.facebook.buck.ide.intellij.model.folders.SourceFolder;
import com.facebook.buck.ide.intellij.model.folders.TestFolder;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;

/**
 * Groups {@link IjFolder}s into sets which are of the same type and belong to the same package
 * structure.
 */
public class IjSourceRootSimplifier {

  private JavaPackageFinder javaPackageFinder;

  public IjSourceRootSimplifier(JavaPackageFinder javaPackageFinder) {
    this.javaPackageFinder = javaPackageFinder;
  }

  /**
   * Merges {@link IjFolder}s of the same type and package prefix.
   *
   * @param simplificationLimit if a path has this many segments it will not be simplified further.
   * @param folders set of {@link IjFolder}s to simplify.
   * @return simplified set of {@link IjFolder}s.
   */
  public ImmutableSet<IjFolder> simplify(int simplificationLimit, ImmutableSet<IjFolder> folders) {
    PackagePathCache packagePathCache = new PackagePathCache(folders, javaPackageFinder);
    BottomUpPathMerger walker =
        new BottomUpPathMerger(folders, simplificationLimit, packagePathCache);

    return walker.getMergedFolders();
  }

  private static class BottomUpPathMerger {
    // Graph where edges represent the parent path -> child path relationship. We need this
    // to efficiently look up children.
    private MutableDirectedGraph<Path> tree;
    // Keeps track of paths which actually have a folder attached to them. It's a bit simpler to
    // use a map like this, especially that the folders then move up the tree as we merge them.
    private Map<Path, IjFolder> mergePathsMap;
    // Efficient package prefix lookup.
    private PackagePathCache packagePathCache;

    public BottomUpPathMerger(
        Iterable<IjFolder> foldersToWalk, int limit, PackagePathCache packagePathCache) {
      this.tree = new MutableDirectedGraph<>();
      this.packagePathCache = packagePathCache;
      this.mergePathsMap = new HashMap<>();

      for (IjFolder folder : foldersToWalk) {
        mergePathsMap.put(folder.getPath(), folder);

        Path path = folder.getPath();
        while (path.getNameCount() > limit) {
          Path parent = path.getParent();
          if (parent == null) {
            break;
          }

          boolean isParentAndGrandParentAlreadyInTree = tree.containsNode(parent);
          tree.addEdge(parent, path);
          if (isParentAndGrandParentAlreadyInTree) {
            break;
          }

          path = parent;
        }
      }
    }

    public ImmutableSet<IjFolder> getMergedFolders() {
      for (Path topLevel : tree.getNodesWithNoIncomingEdges()) {
        walk(topLevel);
      }
      return ImmutableSet.copyOf(mergePathsMap.values());
    }

    /**
     * Walks the trie of paths attempting to merge all of the children of the current path into
     * itself.
     *
     * <p>If a parent folder is present then the merge happens only for children folders that can be
     * merged into a parent folder. Otherwise a parent folder is created and matching children
     * folders are merged into it.
     *
     * @param currentPath current path
     * @return Optional.of(a successfully merged folder) or absent if merging did not succeed.
     */
    private Optional<IjFolder> walk(Path currentPath) {
      ImmutableList<Optional<IjFolder>> children =
          StreamSupport.stream(tree.getOutgoingNodesFor(currentPath).spliterator(), false)
              .map(this::walk)
              .collect(MoreCollectors.toImmutableList());

      ImmutableSet<IjFolder> presentChildren =
          children
              .stream()
              .filter(Optional::isPresent)
              .map(Optional::get)
              .collect(MoreCollectors.toImmutableSet());

      IjFolder currentFolder = mergePathsMap.get(currentPath);
      if (presentChildren.isEmpty()) {
        return Optional.ofNullable(currentFolder);
      }

      boolean hasNonPresentChildren = presentChildren.size() != children.size();

      return tryMergingParentAndChildren(
          currentPath, currentFolder, presentChildren, hasNonPresentChildren);
    }

    /** Tries to merge children to a parent folder. */
    private Optional<IjFolder> tryMergingParentAndChildren(
        Path currentPath,
        @Nullable IjFolder parentFolder,
        ImmutableSet<IjFolder> children,
        boolean hasNonPresentChildren) {
      if (parentFolder == null) {
        return mergeChildrenIntoNewParentFolder(currentPath, children);
      }

      if (parentFolder instanceof SelfMergingOnlyFolder) {
        return Optional.of(parentFolder);
      }

      if ((parentFolder instanceof ExcludeFolder)) {
        if (hasNonPresentChildren
            || children.stream().anyMatch(folder -> !ExcludeFolder.class.isInstance(folder))) {
          return Optional.empty();
        }
        return mergeAndRemoveSimilarChildren(parentFolder, children);
      }

      // SourceFolder or TestFolder
      if (parentFolder.getWantsPackagePrefix()) {
        return mergeFoldersWithMatchingPackageIntoParent(parentFolder, children);
      } else {
        return mergeAndRemoveSimilarChildren(parentFolder, children);
      }
    }

    /**
     * Tries to find the best folder type to create using the types of the children.
     *
     * <p>The best type in this algorithm is the type with the maximum number of children.
     */
    private FolderTypeWithPackageInfo findBestFolderType(ImmutableSet<IjFolder> children) {
      if (children.size() == 1) {
        return FolderTypeWithPackageInfo.fromFolder(children.iterator().next());
      }

      return children
          .stream()
          .collect(
              Collectors.groupingBy(FolderTypeWithPackageInfo::fromFolder, Collectors.counting()))
          .entrySet()
          .stream()
          .max(
              (c1, c2) -> {
                long count1 = c1.getValue();
                long count2 = c2.getValue();
                if (count1 == count2) {
                  return c2.getKey().ordinal() - c1.getKey().ordinal();
                } else {
                  return (int) (count1 - count2);
                }
              })
          .orElseThrow(() -> new IllegalStateException("Max count should exist"))
          .getKey();
    }

    /**
     * Creates a new parent folder and merges children into it.
     *
     * <p>The type of the result folder depends on the children.
     */
    private Optional<IjFolder> mergeChildrenIntoNewParentFolder(
        Path currentPath, ImmutableSet<IjFolder> children) {
      ImmutableSet<IjFolder> childrenToMerge =
          children
              .stream()
              .filter(
                  child ->
                      SourceFolder.class.isInstance(child) || TestFolder.class.isInstance(child))
              .collect(MoreCollectors.toImmutableSet());

      if (childrenToMerge.isEmpty()) {
        return Optional.empty();
      }

      FolderTypeWithPackageInfo typeForMerging = findBestFolderType(childrenToMerge);

      if (typeForMerging.wantsPackagePrefix()) {
        return tryCreateNewParentFolderFromChildrenWithPackage(
            typeForMerging, currentPath, childrenToMerge);
      } else {
        return tryCreateNewParentFolderFromChildrenWithoutPackages(
            typeForMerging, currentPath, childrenToMerge);
      }
    }

    /** Merges either SourceFolders or TestFolders without packages. */
    private Optional<IjFolder> tryCreateNewParentFolderFromChildrenWithoutPackages(
        FolderTypeWithPackageInfo typeForMerging,
        Path currentPath,
        ImmutableSet<IjFolder> children) {
      Class<? extends IjFolder> folderClass = typeForMerging.getFolderTypeClass();
      ImmutableSet<IjFolder> childrenToMerge =
          children
              .stream()
              .filter(folderClass::isInstance)
              .filter(folder -> !folder.getWantsPackagePrefix())
              .collect(MoreCollectors.toImmutableSet());

      if (childrenToMerge.isEmpty()) {
        return Optional.empty();
      }

      IjFolder mergedFolder =
          typeForMerging
              .getFolderFactory()
              .create(
                  currentPath,
                  false,
                  childrenToMerge
                      .stream()
                      .flatMap(folder -> folder.getInputs().stream())
                      .collect(MoreCollectors.toImmutableSortedSet()));

      removeFolders(childrenToMerge);
      mergePathsMap.put(currentPath, mergedFolder);

      return Optional.of(mergedFolder);
    }

    /** Merges either SourceFolders or TestFolders with matching packages. */
    private Optional<IjFolder> tryCreateNewParentFolderFromChildrenWithPackage(
        FolderTypeWithPackageInfo typeForMerging,
        Path currentPath,
        ImmutableSet<IjFolder> children) {
      Optional<Path> currentPackage = packagePathCache.lookup(currentPath);
      if (!currentPackage.isPresent()) {
        return Optional.empty();
      }

      Class<? extends IjFolder> folderClass = typeForMerging.getFolderTypeClass();
      ImmutableSet<IjFolder> childrenToMerge =
          children
              .stream()
              .filter(folderClass::isInstance)
              .filter(IjFolder::getWantsPackagePrefix)
              .filter(
                  child ->
                      canMergeWithKeepingPackage(
                          currentPath, currentPackage.get(), child, packagePathCache))
              .collect(MoreCollectors.toImmutableSet());

      if (childrenToMerge.isEmpty()) {
        return Optional.empty();
      }

      IjFolder mergedFolder =
          typeForMerging
              .getFolderFactory()
              .create(
                  currentPath,
                  true,
                  childrenToMerge
                      .stream()
                      .flatMap(folder -> folder.getInputs().stream())
                      .collect(MoreCollectors.toImmutableSortedSet()));

      removeFolders(childrenToMerge);
      mergePathsMap.put(currentPath, mergedFolder);

      return Optional.of(mergedFolder);
    }

    /**
     * Merges children that have package name matching the parent folder package.
     *
     * <p>For example:
     *
     * <pre>
     * a/b/c (package com.facebook.test)
     * +-----> d (package com.facebook.test.d)
     * +-----> e (package com.facebook.test.f)
     * </pre>
     *
     * <p>will be merged into:
     *
     * <pre>
     * a/b/c (package com.facebook.test)
     * +-----> e (package com.facebook.test.f)
     * </pre>
     */
    private Optional<IjFolder> mergeFoldersWithMatchingPackageIntoParent(
        IjFolder parentFolder, ImmutableSet<IjFolder> children) {

      ImmutableSet<IjFolder> childrenToMerge =
          children
              .stream()
              .filter(child -> canMergeWithKeepingPackage(parentFolder, child, packagePathCache))
              .collect(MoreCollectors.toImmutableSet());

      IjFolder result = mergeFolders(parentFolder, childrenToMerge);

      removeFolders(childrenToMerge);
      mergePathsMap.put(parentFolder.getPath(), result);

      return Optional.of(result);
    }

    /** Merges children that can be merged into a parent. */
    private Optional<IjFolder> mergeAndRemoveSimilarChildren(
        IjFolder parentFolder, ImmutableSet<IjFolder> children) {
      ImmutableSet<IjFolder> childrenToMerge =
          children
              .stream()
              .filter(folder -> folder.canMergeWith(parentFolder))
              .collect(MoreCollectors.toImmutableSet());

      IjFolder result = mergeFolders(parentFolder, childrenToMerge);

      removeFolders(childrenToMerge);
      mergePathsMap.put(result.getPath(), result);

      return Optional.of(result);
    }

    private void removeFolders(Collection<IjFolder> folders) {
      folders.stream().map(IjFolder::getPath).forEach(mergePathsMap::remove);
    }
  }

  /**
   * @return <code>true</code> if parent and child can be merged and they have correct package
   *     structure (child's package name matches parent's package + child's folder name).
   */
  private static boolean canMergeWithKeepingPackage(
      IjFolder parent, IjFolder child, PackagePathCache packagePathCache) {
    Preconditions.checkArgument(child.getPath().startsWith(parent.getPath()));

    if (!child.canMergeWith(parent)) {
      return false;
    }

    Optional<Path> parentPackage = packagePathCache.lookup(parent);
    if (!parentPackage.isPresent()) {
      return false;
    }
    Optional<Path> childPackageOptional = packagePathCache.lookup(child);
    if (!childPackageOptional.isPresent()) {
      return false;
    }
    Path childPackage = childPackageOptional.get();

    int pathDifference = child.getPath().getNameCount() - parent.getPath().getNameCount();
    Preconditions.checkState(
        pathDifference == 1,
        "Path difference is wrong: %s and %s",
        child.getPath(),
        parent.getPath());
    if (childPackage.getNameCount() == 0) {
      return false;
    }
    return MorePaths.getParentOrEmpty(childPackage).equals(parentPackage.get());
  }

  private static boolean canMergeWithKeepingPackage(
      Path currentPath, Path parentPackage, IjFolder child, PackagePathCache packagePathCache) {
    Optional<Path> childPackageOptional = packagePathCache.lookup(child);
    if (!childPackageOptional.isPresent()) {
      return false;
    }
    Path childPackage = childPackageOptional.get();

    int pathDifference = child.getPath().getNameCount() - currentPath.getNameCount();
    Preconditions.checkState(pathDifference == 1);
    if (childPackage.getNameCount() == 0) {
      return false;
    }
    return MorePaths.getParentOrEmpty(childPackage).equals(parentPackage);
  }

  private static IjFolder mergeFolders(IjFolder destinationFolder, Iterable<IjFolder> folders) {
    IjFolder result = destinationFolder;
    for (IjFolder folder : folders) {
      result = folder.merge(result);
    }
    return result;
  }

  /**
   * Hierarchical path cache. If the path a/b/c/d has package c/d it assumes that a/b/c has the
   * package c/.
   */
  private static class PackagePathCache {
    JavaPackagePathCache delegate;

    public PackagePathCache(
        ImmutableSet<IjFolder> startingFolders, JavaPackageFinder javaPackageFinder) {
      delegate = new JavaPackagePathCache();
      for (IjFolder startingFolder : startingFolders) {
        if (!startingFolder.getWantsPackagePrefix()) {
          continue;
        }
        Path path =
            startingFolder.getInputs().stream().findFirst().orElse(lookupPath(startingFolder));
        delegate.insert(path, javaPackageFinder.findJavaPackageFolder(path));
      }
    }

    private Path lookupPath(IjFolder folder) {
      return folder.getPath().resolve("notfound");
    }

    public Optional<Path> lookup(IjFolder folder) {
      return delegate.lookup(lookupPath(folder));
    }

    public Optional<Path> lookup(Path path) {
      return delegate.lookup(path.resolve("notfound"));
    }
  }
}
