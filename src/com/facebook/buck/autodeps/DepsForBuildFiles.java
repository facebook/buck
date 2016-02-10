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
import com.google.common.collect.Iterators;
import com.google.common.collect.SortedSetMultimap;
import com.google.common.collect.TreeMultimap;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedSet;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Glorified typedef for Map<Path, Map<String, Set<BuildTarget>>>.
 * <p>
 * Note that this class is not thread-safe. Currently, it is only used from a single thread. Rather
 * that introduce synchronization to make it multi-threaded in the future, we should create one
 * instance of this class per thread, have each thread consume a discrete portion of the build
 * graph to populate its instance, and then merge all of the data across the instances into a new
 * {@link DepsForBuildFiles} when all of them are finished.
 */
@NotThreadSafe
public class DepsForBuildFiles implements Iterable<DepsForBuildFiles.BuildFileWithDeps> {

  private Map<Path, BuildFileWithDeps> buildFileToDeps;

  public DepsForBuildFiles() {
    this.buildFileToDeps = new HashMap<>();
  }

  public void addDep(BuildTarget rule, BuildTarget dependency) {
    if (rule.equals(dependency)) {
      return;
    }

    Path directory = rule.getCellPath().resolve(rule.getBasePath());
    BuildFileWithDeps buildFileWithDeps = buildFileToDeps.get(directory);
    if (buildFileWithDeps == null) {
      buildFileWithDeps = new BuildFileWithDeps(rule.getCellPath(), rule.getBasePath());
      buildFileToDeps.put(directory, buildFileWithDeps);
    }

    buildFileWithDeps.addDep(rule.getShortName(), dependency);
  }

  @Override
  public Iterator<BuildFileWithDeps> iterator() {
    return buildFileToDeps.values().iterator();
  }

  public static class BuildFileWithDeps implements Iterable<DepsForRule> {

    private final Path cellPath;
    private final Path basePath;
    private final SortedSetMultimap<String, BuildTarget> depsForShortName;

    public BuildFileWithDeps(Path cellPath, Path basePath) {
      this.cellPath = cellPath;
      this.basePath = basePath;
      this.depsForShortName = TreeMultimap.create();
    }

    private void addDep(String shortName, BuildTarget dep) {
      depsForShortName.put(shortName, dep);
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
      return Iterators.transform(depsForShortName.keySet().iterator(),
          new Function<String, DepsForRule>() {
        @Override
        public DepsForRule apply(String shortName) {
          return new DepsForRule(shortName, depsForShortName.get(shortName));
        }
      });
    }
  }

  public static class DepsForRule implements Iterable<BuildTarget> {
    private final String shortName;
    private final SortedSet<BuildTarget> deps;

    private DepsForRule(String shortName, SortedSet<BuildTarget> deps) {
      this.shortName = shortName;
      this.deps = deps;
    }

    public String getShortName() {
      return shortName;
    }

    /**
     * The elements will be enumerated by the {@link Iterator} in lexicographical order and will not
     * contain duplicates.
     */
    @Override
    public Iterator<BuildTarget> iterator() {
      return deps.iterator();
    }
  }
}
