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

package com.facebook.buck.go;

import com.facebook.buck.go.GoListStep.FileType;
import com.facebook.buck.model.BuildTarget;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class FilteredSourceFiles implements Iterable<Path> {
  private final List<Path> rawSrcFiles;
  private Map<Path, GoListStep> filterSteps;

  public FilteredSourceFiles(
      List<Path> rawSrcFiles,
      BuildTarget buildTarget,
      GoToolchain goToolchain,
      GoPlatform platform,
      List<FileType> fileTypes) {
    this.rawSrcFiles = rawSrcFiles;
    initFilterSteps(buildTarget, goToolchain, platform, fileTypes);
  }

  private void initFilterSteps(
      BuildTarget buildTarget, GoToolchain goToolchain, GoPlatform platform, List<FileType> fileTypes) {
    filterSteps = new HashMap<>();
    for (Path srcFile : rawSrcFiles) {
      Path absPath = srcFile.getParent();
      if (!filterSteps.containsKey(absPath)) {
        filterSteps.put(
            absPath, new GoListStep(buildTarget, absPath, goToolchain, platform, fileTypes));
      }
    }
  }

  public Collection<GoListStep> getFilterSteps() {
    return filterSteps.values();
  }

  @Override
  public Iterator<Path> iterator() {
    HashSet<Path> sourceFiles = new HashSet<>();
    for (Path srcFile : rawSrcFiles) {
      if (filterSteps.get(srcFile.getParent()).getSourceFiles().contains(srcFile)) {
        sourceFiles.add(srcFile);
      }
    }
    return sourceFiles.iterator();
  }
}
