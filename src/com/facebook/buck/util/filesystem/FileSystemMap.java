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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
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
    T load(PathFragment path);
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
    @Nullable Map<String, Entry<T>> subLevels = null;

    // The value of the Entry is the actual value the node is associated with:
    //   - If this is a leaf node, value is never null.
    //   - If this is an intermediate node, value is not `null` *only* if we already received a
    //       get() or put() on its path: in this case, its value will be computed and stored.
    //       Otherwise, the node exists only as a mean to reach its children/leaves and will have
    //       a `null` value.
    private volatile @Nullable T value;

    private final PathFragment key;

    private Entry(PathFragment path) {
      // We're creating an empty node here, so it is associated with no value.
      this(path, null);
    }

    private Entry(PathFragment path, @Nullable T value) {
      this.key = path;
      this.value = value;
    }

    @VisibleForTesting
    PathFragment getKey() {
      return key;
    }

    private void set(@Nullable T value) {
      this.value = value;
    }

    @VisibleForTesting
    @Nullable
    T getWithoutLoading() {
      return this.value;
    }

    private void load(ValueLoader<T> loader) {
      if (this.value == null) {
        synchronized (this) {
          if (this.value == null) {
            this.value = loader.load(this.key);
          }
        }
      }
    }

    @VisibleForTesting
    int size() {
      return subLevels == null ? 0 : subLevels.size();
    }
  }

  @VisibleForTesting final Entry<T> root = new Entry<>(PathFragment.create(""));

  @VisibleForTesting
  final ConcurrentHashMap<PathFragment, Entry<T>> map = new ConcurrentHashMap<>();

  private final ValueLoader<T> loader;

  public FileSystemMap(ValueLoader<T> loader) {
    this.loader = loader;
  }

  /**
   * Puts a path and a value into the map.
   *
   * @param path The path to store.
   * @param value The value to associate to the given path.
   */
  public void put(Path path, T value) {
    put(PathFragments.pathToFragment(path), value);
  }

  /** @see #put(Path, Object) */
  public void put(PathFragment fragment, T value) {
    Entry<T> maybe = map.get(fragment);
    if (maybe == null) {
      synchronized (root) {
        maybe = map.computeIfAbsent(fragment, this::putEntry);
      }
    }
    maybe.set(value);
  }

  // Creates the intermediate (and/or the leaf node) if needed and returns the leaf associated
  // with the given path.
  private Entry<T> putEntry(PathFragment path) {
    synchronized (root) {
      Entry<T> parent = root;
      PathFragment relPath = parent.getKey();
      for (String p : path.getSegments()) {
        relPath = relPath.getRelative(p);
        // Create the intermediate node only if it's missing.
        if (parent.subLevels == null) {
          parent.subLevels = new HashMap<>(4);
        }
        PathFragment curPath = relPath; // cheat Java compiler to make it think curPath is final
        parent = parent.subLevels.computeIfAbsent(p, key -> new Entry<>(curPath));
        // parent should never be null.
        Preconditions.checkNotNull(parent);
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
      Stack<Entry<T>> stack = new Stack<>();
      stack.push(root);
      Entry<T> entry = root;
      // Walk the tree to fetch the node requested by the path, or the closest intermediate node.
      boolean partial = false;
      for (Path p : path) {
        if (entry.subLevels == null) {
          partial = true;
          break;
        }
        entry = entry.subLevels.get(p.toString());
        // We're trying to remove a path that doesn't exist, no point in going deeper.
        // Break and proceed to remove whatever path we found so far.
        if (entry == null) {
          partial = true;
          break;
        }
        stack.push(entry);
      }
      // The following approach supports these cases:
      //   1. Remove a path that has been found as a leaf in the trie (easy case).
      //   2. If the path does't exist at the root level, then don't even bother removing anything.
      //   3. We still want to remove paths that "exist partially", that is we haven't found the
      //       requested leaf, but we have found an intermediate node on the branch.
      //   4. Similarly, we want to support prefix removal as well (i.e.: if we want to remove an
      //       intermediate node).
      if (stack.size() > 1) { // check the size in order to address for case #2.
        // Let's take the actual (sub)path we're removing, by using the size of the stack (ignoring
        // the root).
        path = path.subpath(0, stack.size() - 1);
        Entry<T> leaf = stack.pop();
        // If we reached the leaf, then remove the leaf and everything below it (if any).
        if (partial) {
          map.remove(leaf.key);
          if (leaf.size() == 0 && path != null && !stack.empty()) {
            removeChild(stack.peek(), path.getFileName().toString());
          } else {
            leaf.set(null);
          }
        } else {
          removeSubtreeFromMap(leaf);
          removeChild(stack.peek(), path.getFileName().toString());
        }

        // Plus, check everything above in order to remove unused stumps.
        while (!stack.empty()) {
          // This will never throw NPE because if it does, then the stack was empty at the beginning
          // of the iteration (we went upper than the root node, which doesn't make sense).
          path = Preconditions.checkNotNull(path).getParent();
          Entry<T> current = stack.pop();

          // Remove only if it's a cached entry.
          map.remove(current.key);

          if (current.size() == 0 && path != null && !stack.empty()) {
            removeChild(stack.peek(), path.getFileName().toString());
          } else {
            current.set(null);
          }
        }
      }
    }
  }

  // DFS traversal to remove all child nodes from the given node.
  // Must be called while owning a write lock.
  private void removeSubtreeFromMap(Entry<T> leaf) {
    map.remove(leaf.key);
    if (leaf.subLevels != null) {
      leaf.subLevels.values().forEach(this::removeSubtreeFromMap);
    }
  }

  private void removeChild(Entry<T> parent, String childPathString) {
    if (parent.subLevels == null) {
      return;
    }
    parent.subLevels.remove(childPathString);
    if (parent.subLevels.size() == 0) {
      parent.subLevels = null;
    }
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
    return get(PathFragments.pathToFragment(path));
  }

  /** @see #get(Path) */
  public T get(PathFragment fragment) {
    Entry<T> entry = map.get(fragment);
    // get() and remove() shouldn't overlap, but for performance reason (to hold the root lock for
    // less time), we opted for allowing overlap provided that *the entry creation is atomic*.
    // That is, the entry creation is guaranteed to not overlap with anything else, but the entry
    // filling is not: this is because the caller of the get() will still need to get a value,
    // even if the entry is removed meanwhile.
    if (entry == null) {
      synchronized (root) {
        entry = map.computeIfAbsent(fragment, this::putEntry);
      }
    }
    // Maybe here we receive a request for getting an intermediate node (a folder) whose
    // value was never computed before (or has been removed).
    if (entry.value == null) {
      // It is possible that maybe.load() will call back into other methods on this FileSystemMap.
      // Those methods might acquire the root lock. If there's any flow that calls maybe.load()
      // while already holding that lock, there's likely a flow w/ lock inversion.
      Preconditions.checkState(!Thread.holdsLock(root));
      entry.load(loader);
    }
    return Preconditions.checkNotNull(
        entry.value, "Should either be loaded or not null in the first place.");
  }

  /**
   * Gets the value associated with the given path, if found, or `null` otherwise.
   *
   * @param path The path to fetch.
   * @return The value associated with the path.
   */
  @Nullable
  public T getIfPresent(Path path) {
    return getIfPresent(PathFragments.pathToFragment(path));
  }

  /** @see #getIfPresent(Path) */
  @Nullable
  public T getIfPresent(PathFragment fragment) {
    Entry<T> entry = map.get(fragment);
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
          T value = v.value;
          if (value != null) {
            builder.put(PathFragments.fragmentToPath(k), value);
          }
        });
    return builder.build();
  }
}
