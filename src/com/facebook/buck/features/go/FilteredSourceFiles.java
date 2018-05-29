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

package com.facebook.buck.features.go;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.features.go.GoListStep.FileType;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

public class FilteredSourceFiles implements Iterable<Path> {
  private final ImmutableList<Path> rawSrcFiles;
  private final ImmutableList<Path> extraSrcFiles;
  private final ImmutableMap<Path, GoListStep> filterSteps;

  public FilteredSourceFiles(
      List<Path> rawSrcFiles,
      BuildTarget buildTarget,
      GoPlatform platform,
      List<FileType> fileTypes) {
    this(rawSrcFiles, ImmutableList.of(), buildTarget, platform, fileTypes);
  }

  public FilteredSourceFiles(
      List<Path> rawSrcFiles,
      List<Path> extraSrcFiles,
      BuildTarget buildTarget,
      GoPlatform platform,
      List<FileType> fileTypes) {
    this.rawSrcFiles = ImmutableList.copyOf(rawSrcFiles);
    this.extraSrcFiles = ImmutableList.copyOf(extraSrcFiles);
    filterSteps = createFilterSteps(buildTarget, platform, fileTypes);
  }

  private ImmutableMap<Path, GoListStep> createFilterSteps(
      BuildTarget buildTarget, GoPlatform platform, List<FileType> fileTypes) {
    HashMap<Path, GoListStep> filterSteps = new HashMap<>();
    for (Path srcFile : rawSrcFiles) {
      Path absPath = srcFile.getParent();
      if (!filterSteps.containsKey(absPath)) {
        filterSteps.put(absPath, new GoListStep(buildTarget, absPath, platform, fileTypes));
      }
    }
    return ImmutableMap.copyOf(filterSteps);
  }

  public Collection<GoListStep> getFilterSteps() {
    return filterSteps.values();
  }

  @Override
  public Iterator<Path> iterator() {
    HashSet<Path> sourceFiles = new HashSet<>();
    for (Path srcFile : rawSrcFiles) {
      GoListStep step = filterSteps.get(srcFile.getParent());
      Preconditions.checkNotNull(step, "No GoListStep is found for " + srcFile);
      if (step.getSourceFiles().contains(srcFile)) {
        sourceFiles.add(srcFile);
      }
    }
    sourceFiles.addAll(extraSrcFiles);
    return sourceFiles.iterator();
  }
}
