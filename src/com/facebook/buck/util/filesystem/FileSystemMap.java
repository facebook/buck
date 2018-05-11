/*
 * Copyright 2017-present Facebook, Inc.
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
package com.facebook.buck.util.filesystem;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.types.Pair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * This class implements a map for a filesystem structure relying on a prefix tree. The trie only
 * supports relative paths for now, but an effort can be made in order to let it support absolute
 * paths if needed. Every intermediate or leaf node of the trie is a folder/file which may be
 * associated with a value. It's worth noting that adding a value for path foo/bar/file.txt will add
 * intermediate nodes for foo and foo/bar but not values. The value is associated with the specified
 * path and not with its ancestors. Invalidating one of the leaves or intermediate nodes will cause
 * its parent and its ancestors to be invalidated as well: this operation consists in removing the
 * leaf from its parent children and setting the value of all its ancestors to null. If the removal
 * of the target leaf leaves an empty branch (a stump), that is removed as well in order to keep the
 * prefix tree as slim as possible.
 *
 * <p>This class is thread safe in its public methods: concurrent calls to the trie will have the
 * exclusiveness in write/remove operations, while allowing parallel reads to the whole data
 * structure: an attempt could be made to make the trie more concurrent by locking branches of the
 * trie instead of the whole object, although that will require some thought around how to grant
 * parallel writes.
 *
 * @param <T> The type to associate with a specific path.
 */
public class FileSystemMap<T> {

  /**
   * Wrapper class that implements a method for loading a value in the leaves of the trie.
   *
   * @param <T>
   */
  public interface ValueLoader<T> {
    T load(Path path);
  }

  /**
   * Entry is the class representing a file/folder in the prefix tree. Its main responsibilities are
   * to fetch a child of the current folder or the current value, if the entry is a leaf or "inner
   * leaf" - that is an internal node associated with a value.
   *
   * @param <T> The type of the contained value.
   */
  @VisibleForTesting
  static class Entry<T> {
    // Stores all child nodes (i.e. files and subfolders) of the current node
    // Nullable to conserve memory
    @Nullable Map<Path, Entry<T>> subLevels = null;

    // The value of the Entry is the actual value the node is associated with:
    //   - If this is a leaf node, value is never null.
    //   - If this is an intermediate node, value is not `null` *only* if we already received a
    //       get() or put() on its path: in this case, its value will be computed and stored.
    //       Otherwise, the node exists only as a mean to reach its children/leaves and will have
    //       a `null` value.
    private volatile @Nullable T value;

    private Entry() {}

    private void set(@Nullable T value) {
      this.value = value;
    }

    @VisibleForTesting
    @Nullable
    T getWithoutLoading() {
      return this.value;
    }

    private void load(ValueLoader<T> loader, Path path) {
      if (this.value == null) {
        synchronized (this) {
          if (this.value == null) {
            this.value = loader.load(path);
          }
        }
      }
    }

    @VisibleForTesting
    int size() {
      return subLevels == null ? 0 : subLevels.size();
    }
  }

  @VisibleForTesting final Path rootPath;
  @VisibleForTesting final Entry<T> root = new Entry<>();

  @VisibleForTesting final ConcurrentHashMap<Path, Entry<T>> map = new ConcurrentHashMap<>();

  private final ValueLoader<T> loader;

  public FileSystemMap(ValueLoader<T> loader, ProjectFilesystem filesystem) {
    this.loader = loader;
    this.rootPath = filesystem.getPath("");
  }

  /**
   * Puts a path and a value into the map.
   *
   * @param path The path to store.
   * @param value The value to associate to the given path.
   */
  public void put(Path path, T value) {
    Entry<T> maybe = map.get(path);
    if (maybe == null) {
      synchronized (root) {
        maybe = map.computeIfAbsent(path, this::putEntry);
      }
    }
    maybe.set(value);
  }

  // Creates the intermediate (and/or the leaf node) if needed and returns the leaf associated
  // with the given path.
  private Entry<T> putEntry(Path path) {
    synchronized (root) {
      Entry<T> parent = root;
      Path relPath = rootPath;
      for (Path p : path) {
        relPath = relPath.resolve(p);
        if (parent.subLevels == null) {
          // 4 is a magic value we use trying to conserve memory on folders with small amount of
          // files
          parent.subLevels = new HashMap<>(4);
        }
        // Create the intermediate node only if it's missing.
        parent = parent.subLevels.computeIfAbsent(relPath, childPath -> new Entry<>());
      }
      return parent;
    }
  }

  /**
   * Removes the given path.
   *
   * <p>Removing a path involves the following:
   *
   * <ul>
   *   <li>all the child nodes of the given path are discarded as well.
   *   <li>all the paths upstream lose their value.
   *   <li>each path upstream will be removed if after the removal of the leaf the node is left with
   *       no children (that is, it has become a stump).
   * </ul>
   *
   * @param path The path specifying the branch to remove.
   */
  public void remove(Path path) {
    synchronized (root) {
      Stack<Pair<Path, Entry<T>>> stack = new Stack<>();
      Entry<T> entry = root;
      Path relPath = rootPath;
      // Walk the tree to fetch the node requested by the path, or the closest intermediate node.
      boolean partial = false;
      for (Path p : path) {

        // stack will contain all the parent chain but not the actual leaf
        stack.push(new Pair<>(relPath, entry));

        relPath = relPath.resolve(p);
        entry = entry.subLevels == null ? null : entry.subLevels.get(relPath);

        if (entry == null) {
          // We're trying to remove a path that doesn't exist, no point in going deeper.
          // Break and proceed to remove whatever path we found so far.
          partial = true;
          break;
        }
      }
      // The following approach supports these cases:
      //   1. Remove a path that has been found as a leaf in the trie (easy case).
      //   2. Support prefix removal as well (i.e.: if we want to remove an intermediate node.

      if (stack.size() == 0) {
        // this can only happen if path we are trying to remove is empty
        return;
      }

      if (!partial) {
        // If full path is matched, then remove it and everything below it
        removeChild(stack.peek().getSecond(), path);
      }

      // For all paths above, remove intermediate nodes if empty or reset their values if not
      while (!stack.empty()) {
        Pair<Path, Entry<T>> current = stack.pop();

        // dump value on all nodes up, including a root one
        current.getSecond().set(null);

        // remove all parent nodes that do not have children anymore
        if (current.getSecond().size() == 0 && !stack.empty()) {
          removeChild(stack.peek().getSecond(), current.getFirst());
        }
      }
    }
  }

  @SuppressWarnings("NullableProblems")
  private void removeChild(Entry<T> parent, Path childPath) {
    map.remove(childPath);
    Entry<T> child = parent.subLevels.remove(childPath);
    if (parent.subLevels.size() == 0) {
      parent.subLevels = null;
    }

    // remove recursively
    if (child.subLevels == null) {
      return;
    }

    child
        .subLevels
        .keySet()
        .stream()
        // copy collection of keys first to avoid removing from them while iterating
        .collect(Collectors.toList())
        .forEach(cp -> removeChild(child, cp));
  }

  /** Empties the trie leaving only the root node available. */
  public void removeAll() {
    synchronized (root) {
      map.clear();
      root.subLevels = null;
    }
  }

  /**
   * Gets the value associated with the given path.
   *
   * @param path The path to fetch.
   * @return The value associated with the path.
   */
  public T get(Path path) {
    Entry<T> maybe = map.get(path);
    // get() and remove() shouldn't overlap, but for performance reason (to hold the root lock for
    // less time), we opted for allowing overlap provided that *the entry creation is atomic*.
    // That is, the entry creation is guaranteed to not overlap with anything else, but the entry
    // filling is not: this is because the caller of the get() will still need to get a value,
    // even if the entry is removed meanwhile.
    if (maybe == null) {
      synchronized (root) {
        maybe = map.computeIfAbsent(path, this::putEntry);
      }
    }
    // Maybe here we receive a request for getting an intermediate node (a folder) whose
    // value was never computed before (or has been removed).
    if (maybe.value == null) {
      // It is possible that maybe.load() will call back into other methods on this FileSystemMap.
      // Those methods might acquire the root lock. If there's any flow that calls maybe.load()
      // while already holding that lock, there's likely a flow w/ lock inversion.
      Preconditions.checkState(!Thread.holdsLock(root));
      maybe.load(loader, path);
    }
    return maybe.value;
  }

  /**
   * Gets the value associated with the given path, if found, or `null` otherwise.
   *
   * @param path The path to fetch.
   * @return The value associated with the path.
   */
  @Nullable
  public T getIfPresent(Path path) {
    Entry<T> entry = map.get(path);
    return entry == null ? null : entry.value;
  }

  /**
   * Returns a copy of the leaves stored in the trie as a map. N.B.: this is quite an expensive call
   * to make, so use it wisely.
   */
  public ImmutableMap<Path, T> asMap() {
    ImmutableMap.Builder<Path, T> builder = ImmutableMap.builder();
    map.forEach(
        (k, v) -> {
          if (v.value != null) {
            builder.put(k, v.value);
          }
        });
    return builder.build();
  }
}
