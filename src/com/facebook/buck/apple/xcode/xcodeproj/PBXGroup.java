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

package com.facebook.buck.apple.xcode.xcodeproj;

import com.facebook.buck.apple.xcode.AbstractPBXObjectFactory;
import com.facebook.buck.apple.xcode.XcodeprojSerializer;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import javax.annotation.Nullable;

/** A collection of files in Xcode's virtual filesystem hierarchy. */
public class PBXGroup extends PBXReference {
  /** Method by which group contents will be sorted. */
  public enum SortPolicy {
    /** By name, in default Java sort order. */
    BY_NAME,

    /** Group contents will not be sorted, and will remain in the order they were added. */
    UNSORTED
  }

  // Unfortunately, we can't determine this at constructor time, because CacheBuilder
  // calls our constructor and it's not easy to pass arguments to it.
  private SortPolicy sortPolicy;

  private final List<PBXReference> children;

  private final LoadingCache<String, PBXGroup> childGroupsByName;
  private final LoadingCache<String, PBXVariantGroup> childVariantGroupsByName;
  private final LoadingCache<SourceTreePath, PBXFileReference> fileReferencesBySourceTreePath;
  private final LoadingCache<SourceTreePath, XCVersionGroup> childVersionGroupsBySourceTreePath;

  /**
   * Cache loader for a PBXGroup type. If `setPath` is true, it will automatically set the path
   * reference of the PBXGroup. This fork is to maintain backwards compatibility with projectv1;
   * once v1 is removed, this code can be simplified and this layer of indirection removed.
   */
  private static class PBXGroupCacheLoader<T extends PBXGroup> extends CacheLoader<String, T> {
    private final BiFunction<String, String, T> objectLoader;
    private final boolean setPath;

    public PBXGroupCacheLoader(BiFunction<String, String, T> objectLoader, boolean setPath) {
      this.objectLoader = objectLoader;
      this.setPath = setPath;
    }

    @Override
    public T load(String key) {
      if (this.setPath) {
        return this.objectLoader.apply(key, key);
      }

      return this.objectLoader.apply(key, null);
    }
  }

  public PBXGroup(
      String name,
      @Nullable String path,
      SourceTree sourceTree,
      AbstractPBXObjectFactory objectFactory) {
    super(name, path, sourceTree);

    sortPolicy = SortPolicy.BY_NAME;
    children = new ArrayList<>();

    childGroupsByName =
        CacheBuilder.newBuilder()
            .build(
                new PBXGroupCacheLoader<>(
                    (key, groupPath) -> {
                      PBXGroup group =
                          objectFactory.createPBXGroup(key, groupPath, SourceTree.GROUP);
                      children.add(group);
                      return group;
                    },
                    path != null));

    childVariantGroupsByName =
        CacheBuilder.newBuilder()
            .build(
                new PBXGroupCacheLoader<>(
                    (key, groupPath) -> {
                      PBXVariantGroup group =
                          objectFactory.createVariantGroup(key, groupPath, SourceTree.GROUP);
                      children.add(group);
                      return group;
                    },
                    path != null));

    fileReferencesBySourceTreePath =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<SourceTreePath, PBXFileReference>() {
                  @Override
                  public PBXFileReference load(SourceTreePath key) {
                    PBXFileReference ref = key.createFileReference(objectFactory);
                    children.add(ref);
                    return ref;
                  }
                });

    childVersionGroupsBySourceTreePath =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<SourceTreePath, XCVersionGroup>() {
                  @Override
                  public XCVersionGroup load(SourceTreePath key) {
                    XCVersionGroup ref = key.createVersionGroup(objectFactory);
                    children.add(ref);
                    return ref;
                  }
                });
  }

  public PBXGroup getOrCreateChildGroupByName(String name) {
    return childGroupsByName.getUnchecked(name);
  }

  public PBXGroup getOrCreateDescendantGroupByPath(ImmutableList<String> path) {
    PBXGroup targetGroup = this;
    for (String part : path) {
      targetGroup = targetGroup.getOrCreateChildGroupByName(part);
    }
    return targetGroup;
  }

  public PBXVariantGroup getOrCreateChildVariantGroupByName(String name) {
    return childVariantGroupsByName.getUnchecked(name);
  }

  public PBXFileReference getOrCreateFileReferenceBySourceTreePath(SourceTreePath sourceTreePath) {
    return fileReferencesBySourceTreePath.getUnchecked(sourceTreePath);
  }

  public XCVersionGroup getOrCreateChildVersionGroupsBySourceTreePath(
      SourceTreePath sourceTreePath) {
    return childVersionGroupsBySourceTreePath.getUnchecked(sourceTreePath);
  }

  public List<PBXReference> getChildren() {
    return children;
  }

  public void setSortPolicy(SortPolicy sortPolicy) {
    this.sortPolicy = sortPolicy;
  }

  @Override
  public String isa() {
    return "PBXGroup";
  }

  @Override
  public void serializeInto(XcodeprojSerializer s) {
    super.serializeInto(s);

    if (sortPolicy == SortPolicy.BY_NAME) {
      Collections.sort(children, (o1, o2) -> o1.getName().compareTo(o2.getName()));
    }

    s.addField("children", children);
  }
}
