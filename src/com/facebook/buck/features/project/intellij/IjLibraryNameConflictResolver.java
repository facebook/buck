/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.features.project.intellij.model.IjLibrary;
import com.google.common.base.Preconditions;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A utility to resolve conflicts in naming library xml files under .idea/libraries.
 *
 * <p>For eg: The two libraries `//foo/bar:baz` and `//foo_bar:baz`, they will have the same
 * filename: .idea/libraries/__foo_bar_baz.xml
 *
 * <p>This class will resolve the conflict by assigning a suffix as below:
 *
 * <p>//foo/bar:baz => __foo_bar_baz.xml
 *
 * <p>//foo_bar:baz => __foo_bar_baz2.xml
 *
 * <p>Sorting the conflicted library names helps to resolve them deterministically.
 */
public class IjLibraryNameConflictResolver {

  private final Integer PLACEHOLDER_INDEX_VALUE = 1;
  private final Map<String, String> normalizedNameMap;
  private final Map<String, TreeMap<String, Integer>> libraryNameConflictMap;

  public IjLibraryNameConflictResolver(IjProjectTemplateDataPreparer projectDataPreparer) {
    normalizedNameMap = new HashMap<>();
    libraryNameConflictMap = new HashMap<>();

    projectDataPreparer.getProjectLibrariesToBeWritten().forEach(this::addToConflictMap);

    addIndicesToConflictMap();
  }

  /** Resolves the conflict in project libraries by assigning a suffix */
  public String resolve(String libName) {
    Preconditions.checkArgument(normalizedNameMap.containsKey(libName));
    String normalizedName = normalizedNameMap.get(libName);

    Preconditions.checkArgument(libraryNameConflictMap.containsKey(normalizedName));
    Integer index = libraryNameConflictMap.get(normalizedName).get(libName);

    if (index > PLACEHOLDER_INDEX_VALUE) {
      return normalizedName + index;
    }
    return normalizedName;
  }

  private void addToConflictMap(IjLibrary library) {
    String normalizedName = Util.normalizeIntelliJName(library.getName());
    normalizedNameMap.put(library.getName(), normalizedName);
    libraryNameConflictMap.putIfAbsent(normalizedName, new TreeMap<>());
    libraryNameConflictMap.get(normalizedName).put(library.getName(), PLACEHOLDER_INDEX_VALUE);
  }

  private void addIndicesToConflictMap() {
    libraryNameConflictMap.values().stream()
        .filter(conflictedNamesMap -> conflictedNamesMap.size() > 1)
        .forEach(
            conflictedNamesMap -> {
              AtomicInteger index = new AtomicInteger(PLACEHOLDER_INDEX_VALUE);
              conflictedNamesMap
                  .keySet()
                  .forEach(libName -> conflictedNamesMap.put(libName, index.getAndIncrement()));
            });
  }
}
