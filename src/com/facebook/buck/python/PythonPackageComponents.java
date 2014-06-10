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

import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.concurrent.Immutable;

@Immutable
public class PythonPackageComponents {

  // Python modules as map of their module name to location of the source.
  private final ImmutableMap<Path, Path> modules;

  // Resources to include in the package.
  private final ImmutableMap<Path, Path> resources;

  // Native libraries to include in the package.
  private final ImmutableMap<Path, Path> nativeLibraries;

  public PythonPackageComponents(
      ImmutableMap<Path, Path> modules,
      ImmutableMap<Path, Path> resources,
      ImmutableMap<Path, Path> nativeLibraries) {
    this.modules = Preconditions.checkNotNull(modules);
    this.resources = Preconditions.checkNotNull(resources);
    this.nativeLibraries = Preconditions.checkNotNull(nativeLibraries);
  }

  public ImmutableMap<Path, Path> getModules() {
    return modules;
  }

  public ImmutableMap<Path, Path> getResources() {
    return resources;
  }

  public ImmutableMap<Path, Path> getNativeLibraries() {
    return nativeLibraries;
  }

  @Override
  public String toString() {
    return "PythonPackageComponents{" +
        "modules=" + modules +
        ", resources=" + resources +
        ", nativeLibraries=" + nativeLibraries +
        '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    PythonPackageComponents that = (PythonPackageComponents) o;

    if (modules != null ? !modules.equals(that.modules) : that.modules != null) {
      return false;
    }
    if (nativeLibraries != null ?
        !nativeLibraries.equals(that.nativeLibraries) :
        that.nativeLibraries != null) {
      return false;
    }
    if (resources != null ? !resources.equals(that.resources) : that.resources != null) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    int result = modules != null ? modules.hashCode() : 0;
    result = 31 * result + (resources != null ? resources.hashCode() : 0);
    result = 31 * result + (nativeLibraries != null ? nativeLibraries.hashCode() : 0);
    return result;
  }

  /**
   * A helper class to construct a PythonPackageComponents instance which
   * throws human readable error messages on duplicates.
   */
  public static class Builder {

    // A description of the entity that is building this PythonPackageComponents instance.
    private final String owner;

    // The actual maps holding the components.
    private final ImmutableMap.Builder<Path, Path> modules = ImmutableMap.builder();
    private final ImmutableMap.Builder<Path, Path> resources = ImmutableMap.builder();
    private final ImmutableMap.Builder<Path, Path> nativeLibraries = ImmutableMap.builder();

    // Bookkeeping used to for error handling in the presence of duplicate
    // entries.  These data structures map the components named above to the
    // entities that provided them.
    private final Map<Path, String> moduleSources = new HashMap<>();
    private final Map<Path, String> resourceSources = new HashMap<>();
    private final Map<Path, String> nativeLibrarySources = new HashMap<>();

    public Builder(String owner) {
      this.owner = Preconditions.checkNotNull(owner);
    }

    private HumanReadableException createDuplicateError(
        String type,
        Path destination,
        String sourceA,
        String sourceB) {
      return new HumanReadableException(
          "%s: found duplicate entries for %s %s when creating python package (%s and %s)",
          owner, type, destination, sourceA, sourceB);
    }

    private Builder add(
        String type,
        ImmutableMap.Builder<Path, Path> builder,
        Map<Path, String> sourceDescs,
        Path destination,
        Path source,
        String sourceDesc) {
      String existing = sourceDescs.get(destination);
      if (existing != null) {
        throw createDuplicateError(type, destination, sourceDesc, existing);
      }
      builder.put(destination, source);
      sourceDescs.put(destination, sourceDesc);
      return this;
    }

    private Builder add(
        String type,
        ImmutableMap.Builder<Path, Path> builder,
        Map<Path, String> sourceDescs,
        ImmutableMap<Path, Path> toAdd,
        String sourceDesc) {
      for (ImmutableMap.Entry<Path, Path> ent : toAdd.entrySet()) {
        add(type, builder, sourceDescs, ent.getKey(), ent.getValue(), sourceDesc);
      }
      return this;
    }

    public Builder addModule(Path destination, Path source, String from) {
      return add("module", modules, moduleSources, destination, source, from);
    }

    public Builder addModules(ImmutableMap<Path, Path> sources, String from) {
      return add("module", modules, moduleSources, sources, from);
    }

    public Builder addResource(Path destination, Path source, String from) {
      return add("resource", resources, resourceSources, destination, source, from);
    }

    public Builder addResources(ImmutableMap<Path, Path> sources, String from) {
      return add("resource", resources, resourceSources, sources, from);
    }

    public Builder addNativeLibrary(Path destination, Path source, String from) {
      return add(
          "native library",
          nativeLibraries,
          nativeLibrarySources,
          destination,
          source,
          from);
    }

    public Builder addNativeLibraries(ImmutableMap<Path, Path> sources, String from) {
      return add("native library", nativeLibraries, nativeLibrarySources, sources, from);
    }

    public Builder addComponent(PythonPackageComponents other, String from) {
      addModules(other.getModules(), from);
      addResources(other.getResources(), from);
      addNativeLibraries(other.getNativeLibraries(), from);
      return this;
    }

    public PythonPackageComponents build() {
      return new PythonPackageComponents(
          modules.build(),
          resources.build(),
          nativeLibraries.build());
    }

  }

}
