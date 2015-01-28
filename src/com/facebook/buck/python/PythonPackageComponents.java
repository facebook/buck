/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.python;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableMap;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Value.Immutable(builder = false)
@BuckStyleImmutable
public abstract class PythonPackageComponents {

  // Python modules as map of their module name to location of the source.
  @Value.Parameter
  public abstract Map<Path, SourcePath> getModules();

  // Resources to include in the package.
  @Value.Parameter
  public abstract Map<Path, SourcePath> getResources();

  // Native libraries to include in the package.
  @Value.Parameter
  public abstract Map<Path, SourcePath> getNativeLibraries();

  /**
   * A helper class to construct a PythonPackageComponents instance which
   * throws human readable error messages on duplicates.
   */
  public static class Builder {

    // A description of the entity that is building this PythonPackageComponents instance.
    private final BuildTarget owner;

    // The actual maps holding the components.
    private final ImmutableMap.Builder<Path, SourcePath> modules = ImmutableMap.builder();
    private final ImmutableMap.Builder<Path, SourcePath> resources = ImmutableMap.builder();
    private final ImmutableMap.Builder<Path, SourcePath> nativeLibraries = ImmutableMap.builder();

    // Bookkeeping used to for error handling in the presence of duplicate
    // entries.  These data structures map the components named above to the
    // entities that provided them.
    private final Map<Path, BuildTarget> moduleSources = new HashMap<>();
    private final Map<Path, BuildTarget> resourceSources = new HashMap<>();
    private final Map<Path, BuildTarget> nativeLibrarySources = new HashMap<>();

    public Builder(BuildTarget owner) {
      this.owner = owner;
    }

    private HumanReadableException createDuplicateError(
        String type,
        Path destination,
        BuildTarget sourceA,
        BuildTarget sourceB) {
      return new HumanReadableException(
          "%s: found duplicate entries for %s %s when creating python package (%s and %s)",
          owner, type, destination, sourceA, sourceB);
    }

    private Builder add(
        String type,
        ImmutableMap.Builder<Path, SourcePath> builder,
        Map<Path, BuildTarget> sourceDescs,
        Path destination,
        SourcePath source,
        BuildTarget sourceDesc) {
      BuildTarget existing = sourceDescs.get(destination);
      if (existing != null) {
        throw createDuplicateError(type, destination, sourceDesc, existing);
      }
      builder.put(destination, source);
      sourceDescs.put(destination, sourceDesc);
      return this;
    }

    private Builder add(
        String type,
        ImmutableMap.Builder<Path, SourcePath> builder,
        Map<Path, BuildTarget> sourceDescs,
        Map<Path, SourcePath> toAdd,
        BuildTarget sourceDesc) {
      for (Map.Entry<Path, SourcePath> ent : toAdd.entrySet()) {
        add(type, builder, sourceDescs, ent.getKey(), ent.getValue(), sourceDesc);
      }
      return this;
    }

    public Builder addModule(Path destination, SourcePath source, BuildTarget from) {
      return add("module", modules, moduleSources, destination, source, from);
    }

    public Builder addModules(Map<Path, SourcePath> sources, BuildTarget from) {
      return add("module", modules, moduleSources, sources, from);
    }

    public Builder addResource(Path destination, SourcePath source, BuildTarget from) {
      return add("resource", resources, resourceSources, destination, source, from);
    }

    public Builder addResources(Map<Path, SourcePath> sources, BuildTarget from) {
      return add("resource", resources, resourceSources, sources, from);
    }

    public Builder addNativeLibrary(Path destination, SourcePath source, BuildTarget from) {
      return add(
          "native library",
          nativeLibraries,
          nativeLibrarySources,
          destination,
          source,
          from);
    }

    public Builder addNativeLibraries(Map<Path, SourcePath> sources, BuildTarget from) {
      return add("native library", nativeLibraries, nativeLibrarySources, sources, from);
    }

    public Builder addComponent(PythonPackageComponents other, BuildTarget from) {
      addModules(other.getModules(), from);
      addResources(other.getResources(), from);
      addNativeLibraries(other.getNativeLibraries(), from);
      return this;
    }

    public PythonPackageComponents build() {
      return ImmutablePythonPackageComponents.of(
          modules.build(),
          resources.build(),
          nativeLibraries.build());
    }

  }

}
