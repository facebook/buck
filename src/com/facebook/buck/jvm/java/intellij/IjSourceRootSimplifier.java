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
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

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
   * @param limit if a path has this many segments it will not be simplified further.
   * @param folders set of {@link IjFolder}s to simplify.
   * @return simplified set of {@link IjFolder}s.
   */
  public ImmutableSet<IjFolder> simplify(
      SimplificationLimit limit,
      ImmutableSet<IjFolder> folders) {
    PackagePathCache packagePathCache = new PackagePathCache(folders, javaPackageFinder);
    BottomUpPathMerger walker =
        new BottomUpPathMerger(folders, limit.getValue(), packagePathCache);

    return walker.getMergedFolders();
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
          StreamSupport.stream(tree.getOutgoingNodesFor(currentPath).spliterator(), false)
              .map(this::walk)
              .collect(MoreCollectors.toImmutableList());

      List<IjFolder> presentChildren = new ArrayList<>(children.size());
      for (Optional<IjFolder> folderOptional : children) {
        if (!folderOptional.isPresent()) {
          return Optional.empty();
        }

        IjFolder folder = folderOptional.get();
        // We don't want to merge exclude folders.
        if (folder instanceof ExcludeFolder) {
          continue;
        }
        presentChildren.add(folderOptional.get());
      }

      IjFolder currentFolder = mergePathsMap.get(currentPath);
      if (presentChildren.isEmpty()) {
        return Optional.ofNullable(currentFolder);
      }

      final IjFolder mergeDistination;
      if (currentFolder != null) {
        mergeDistination = currentFolder;
      } else {
        mergeDistination =
            findBestChildToAggregateTo(presentChildren)
              .createCopyWith(currentPath);
      }

      boolean allChildrenCanBeMerged = FluentIterable.from(presentChildren)
          .allMatch(
              input -> canMerge(mergeDistination, input, packagePathCache));
      if (!allChildrenCanBeMerged) {
        return Optional.empty();
      }

      return attemptMerge(mergeDistination, presentChildren);
    }

    private Optional<IjFolder> attemptMerge(IjFolder mergePoint, Collection<IjFolder> children) {
      List<Path> mergedPaths = new ArrayList<>(children.size());
      for (IjFolder presentChild : children) {
        mergedPaths.add(presentChild.getPath());
        if (!canMerge(mergePoint, presentChild, packagePathCache)) {
          return Optional.empty();
        }
        mergePoint = presentChild.merge(mergePoint);
      }

      for (Path path : mergedPaths) {
        mergePathsMap.remove(path);
      }
      mergePathsMap.put(mergePoint.getPath(), mergePoint);

      return Optional.of(mergePoint);
    }
  }

  /**
   * Find a child which can be used as the aggregation point for the other folders.
   * The order of preference is;
   * - AndroidResource - because there should be only one
   * - SourceFolder - because most things should merge into it
   * - First Child - because no other folders significantly affect aggregation.
   */
  private static IjFolder findBestChildToAggregateTo(Iterable<IjFolder> children) {
    Iterator<IjFolder> childIterator = children.iterator();

    IjFolder bestCandidate = childIterator.next();
    while (childIterator.hasNext()) {
      IjFolder candidate = childIterator.next();

      if (candidate instanceof AndroidResourceFolder) {
        return candidate;
      }

      if (candidate instanceof SourceFolder) {
        bestCandidate = candidate;
      }
    }

    return bestCandidate;
  }

  private static boolean canMerge(
      IjFolder parent,
      IjFolder child,
      PackagePathCache packagePathCache) {
    Preconditions.checkArgument(child.getPath().startsWith(parent.getPath()));

    if (!child.canMergeWith(parent)) {
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
        Path path = startingFolder.getInputs().stream().findFirst()
            .orElse(lookupPath(startingFolder));
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
