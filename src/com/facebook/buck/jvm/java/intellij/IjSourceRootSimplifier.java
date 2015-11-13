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

package com.facebook.buck.jvm.java.intellij;

import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.jvm.java.JavaPackageFinder;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Groups {@link IjFolder}s into sets which are of the same type and belong to the same package
 * structure.
 */
public class IjSourceRootSimplifier {

  private JavaPackageFinder javaPackageFinder;

  public IjSourceRootSimplifier(JavaPackageFinder javaPackageFinder) {
    this.javaPackageFinder = javaPackageFinder;
  }

  private static final ImmutableSet<IjFolder.Type> MERGABLE_FOLDER_TYPES =
      ImmutableSet.of(AbstractIjFolder.Type.SOURCE_FOLDER, AbstractIjFolder.Type.TEST_FOLDER);

  /**
   * Merges {@link IjFolder}s of the same type and package prefix.
   *
   * @param limit if a path has this many segments it will not be simplified further.
   * @param folders set of {@link IjFolder}s to simplify.
   * @return simplified set of {@link IjFolder}s.
   */
  public ImmutableSet<IjFolder> simplify(
      SimplificationLimit limit,
      ImmutableSet<IjFolder> folders) {

    ImmutableSet.Builder<IjFolder> mergedFoldersBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<IjFolder> foldersToMergeBuilder = ImmutableSet.builder();

    for (IjFolder folder : folders) {
      if (!MERGABLE_FOLDER_TYPES.contains(folder.getType())) {
        mergedFoldersBuilder.add(folder);
      } else {
        foldersToMergeBuilder.add(folder);
      }
    }

    ImmutableSet<IjFolder> foldersToMerge = foldersToMergeBuilder.build();
    PackagePathCache packagePathCache = new PackagePathCache(foldersToMerge, javaPackageFinder);
    BottomUpPathMerger walker =
        new BottomUpPathMerger(foldersToMerge, limit.getValue(), packagePathCache);

    return mergedFoldersBuilder
        .addAll(walker.getMergedFolders())
        .build();
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractSimplificationLimit {
    @Value.Parameter
    public abstract int getValue();
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
        Iterable<IjFolder> foldersToWalk,
        int limit,
        PackagePathCache packagePathCache) {
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
     * itself. As soon as this fails we know we can't merge the parent path with the current path
     * either.
     *
     * @param currentPath current path
     * @return Optional.of(a successfully merged folder) or absent if merging did not succeed.
     */
    private Optional<IjFolder> walk(Path currentPath) {
      ImmutableList<Optional<IjFolder>> children =
          FluentIterable.from(tree.getOutgoingNodesFor(currentPath))
              .transform(
                  new Function<Path, Optional<IjFolder>>() {
                    @Override
                    public Optional<IjFolder> apply(Path input) {
                      return walk(input);
                    }
                  })
              .toList();

      boolean anyAbsent = FluentIterable.from(children)
          .anyMatch(Predicates.equalTo(Optional.<IjFolder>absent()));
      if (anyAbsent) {
        // Signal that no further merging should be done.
        return Optional.absent();
      }

      ImmutableSet<IjFolder> presentChildren = FluentIterable.from(children)
          .transform(
              new Function<Optional<IjFolder>, IjFolder>() {
                @Override
                public IjFolder apply(Optional<IjFolder> input) {
                  return input.get();
                }
              })
          .toSet();
      IjFolder currentFolder = mergePathsMap.get(currentPath);
      if (presentChildren.isEmpty()) {
        return Optional.of(Preconditions.checkNotNull(currentFolder));
      }

      final IjFolder myFolder;
      if (currentFolder != null) {
        myFolder = currentFolder;
      } else {
        IjFolder aChild = presentChildren.iterator().next();
        myFolder = aChild
            .withInputs(ImmutableSortedSet.<Path>of())
            .withPath(currentPath);
      }
      boolean allChildrenCanBeMerged = FluentIterable.from(presentChildren)
          .allMatch(
              new Predicate<IjFolder>() {
                @Override
                public boolean apply(IjFolder input) {
                  return canMerge(myFolder, input, packagePathCache);
                }
              });
      if (!allChildrenCanBeMerged) {
        return Optional.absent();
      }
      IjFolder mergedFolder = myFolder;
      for (IjFolder presentChild : presentChildren) {
        mergePathsMap.remove(presentChild.getPath());
        mergedFolder = presentChild.merge(mergedFolder);
      }
      mergePathsMap.put(mergedFolder.getPath(), mergedFolder);

      return Optional.of(mergedFolder);
    }

  }

  private static boolean canMerge(
      IjFolder parent,
      IjFolder child,
      PackagePathCache packagePathCache) {
    Preconditions.checkArgument(child.getPath().startsWith(parent.getPath()));

    if (parent.getType() != child.getType()) {
      return false;
    }
    if (parent.getWantsPackagePrefix() != child.getWantsPackagePrefix()) {
      return false;
    }
    if (parent.getWantsPackagePrefix()) {
      Optional<Path> parentPackage = packagePathCache.lookup(parent);
      if (!parentPackage.isPresent()) {
        return false;
      }
      Path childPackage = packagePathCache.lookup(child).get();

      int pathDifference = child.getPath().getNameCount() - parent.getPath().getNameCount();
      Preconditions.checkState(pathDifference == 1);
      if (childPackage.getNameCount() == 0) {
        return false;
      }
      if (!MorePaths.getParentOrEmpty(childPackage).equals(parentPackage.get())) {
        return false;
      }
    }
    return true;
  }

  /**
   * Hierarchical path cache. If the path a/b/c/d has package c/d it assumes that
   * a/b/c has the package c/.
   */
  private static class PackagePathCache {
    ParsingJavaPackageFinder.PackagePathCache delegate;

    public PackagePathCache(
        ImmutableSet<IjFolder> startingFolders,
        JavaPackageFinder javaPackageFinder) {
      delegate = new ParsingJavaPackageFinder.PackagePathCache();
      for (IjFolder startingFolder : startingFolders) {
        if (!startingFolder.getWantsPackagePrefix()) {
          continue;
        }
        Path path = FluentIterable.from(startingFolder.getInputs())
            .first()
            .or(lookupPath(startingFolder));
        delegate.insert(path, javaPackageFinder.findJavaPackageFolder(path));
      }
    }

    private Path lookupPath(IjFolder folder) {
      return folder.getPath().resolve("notfound");
    }

    public Optional<Path> lookup(IjFolder folder) {
      return delegate.lookup(lookupPath(folder));
    }
  }

}
