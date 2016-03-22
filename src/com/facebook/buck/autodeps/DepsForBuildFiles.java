/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.autodeps;

import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Glorified typedef for Map<Path, Map<String, EnumMap<DependencyType, SortedSet<BuildTarget>>>>.
 * <p>
 * Note that this class is not thread-safe. Currently, it is only used from a single thread. Rather
 * that introduce synchronization to make it multi-threaded in the future, we should create one
 * instance of this class per thread, have each thread consume a discrete portion of the build
 * graph to populate its instance, and then merge all of the data across the instances into a new
 * {@link DepsForBuildFiles} when all of them are finished.
 */
@NotThreadSafe
public class DepsForBuildFiles implements Iterable<DepsForBuildFiles.BuildFileWithDeps> {

  public static enum DependencyType {
    DEPS,
    EXPORTED_DEPS,
  }

  private Map<Path, BuildFileWithDeps> buildFileToDeps;

  public DepsForBuildFiles() {
    this.buildFileToDeps = new HashMap<>();
  }

  public void addDep(BuildTarget rule, BuildTarget dependency, DependencyType type) {
    if (rule.equals(dependency)) {
      return;
    }

    Path directory = rule.getCellPath().resolve(rule.getBasePath());
    BuildFileWithDeps buildFileWithDeps = buildFileToDeps.get(directory);
    if (buildFileWithDeps == null) {
      buildFileWithDeps = new BuildFileWithDeps(rule.getCellPath(), rule.getBasePath());
      buildFileToDeps.put(directory, buildFileWithDeps);
    }

    buildFileWithDeps.addDep(rule.getShortName(), dependency, type);
  }

  @Override
  public Iterator<BuildFileWithDeps> iterator() {
    return buildFileToDeps.values().iterator();
  }

  public static class BuildFileWithDeps implements Iterable<DepsForRule> {

    private final Path cellPath;
    private final Path basePath;
    private final Map<String, EnumMap<DependencyType, Set<BuildTarget>>> deps;

    public BuildFileWithDeps(Path cellPath, Path basePath) {
      this.cellPath = cellPath;
      this.basePath = basePath;
      this.deps = new HashMap<>();
    }

    private void addDep(String shortName, BuildTarget dep, DependencyType type) {
      EnumMap<DependencyType, Set<BuildTarget>> map = deps.get(shortName);
      if (map == null) {
        map = new EnumMap<>(DependencyType.class);
        for (DependencyType enumValue : DependencyType.values()) {
          map.put(enumValue, new TreeSet<BuildTarget>());
        }
        deps.put(shortName, map);
      }
      map.get(type).add(dep);
    }

    public Path getCellPath() {
      return cellPath;
    }

    public Path getBasePath() {
      return basePath;
    }

    /**
     * The ordering of the elements in this {@link Iterator} is unspecified.
     */
    @Override
    public Iterator<DepsForRule> iterator() {
      return Iterators.transform(deps.entrySet().iterator(),
          new Function<Map.Entry<String,
                                 EnumMap<DependencyType, Set<BuildTarget>>>, DepsForRule>() {
        @Override
        public DepsForRule apply(
            Map.Entry<String, EnumMap<DependencyType, Set<BuildTarget>>> entry) {
          return new DepsForRule(entry.getKey(), entry.getValue());
        }
      });
    }
  }

  public static class DepsForRule {
    private final String shortName;
    private final ImmutableSortedSet<BuildTarget> requiredDeps;
    private final ImmutableSortedSet<BuildTarget> exportedDeps;

    private DepsForRule(String shortName, EnumMap<DependencyType, Set<BuildTarget>> deps) {
      this.shortName = shortName;
      this.exportedDeps = ImmutableSortedSet.copyOf(deps.get(DependencyType.EXPORTED_DEPS));
      this.requiredDeps = ImmutableSortedSet.copyOf(
          Sets.difference(deps.get(DependencyType.DEPS), exportedDeps));
    }

    public String getShortName() {
      return shortName;
    }

    /**
     * The elements will be enumerated by the {@link Iterator} in lexicographical order and will not
     * contain duplicates.
     */
    public Iterable<BuildTarget> depsForDependencyType(DependencyType type) {
      switch (type) {
      case DEPS:
        return requiredDeps;
      case EXPORTED_DEPS:
        return exportedDeps;
      default:
        throw new IllegalArgumentException("Unrecognized type: " + type);
      }
    }
  }
}
