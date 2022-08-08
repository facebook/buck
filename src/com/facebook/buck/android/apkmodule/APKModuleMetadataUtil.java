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

package com.facebook.buck.android.apkmodule;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import com.google.common.collect.TreeMultimap;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.function.Function;

public class APKModuleMetadataUtil {

  public static final String CLASS_SECTION_HEADER = "CLASSES";
  public static final String TARGETS_SECTION_HEADER = "TARGETS";
  public static final String DEPS_SECTION_HEADER = "DEPS";
  public static final String LIBRARIES_SECTION_HEADER = "LIBRARIES";
  public static final String BLACKLIST_SECTION_HEADER = "BLACKLIST";
  public static final String MODULE_INDENTATION = "  ";
  public static final String ITEM_INDENTATION = "    ";

  public static <BuildTarget extends Comparable<BuildTarget>> List<String> getMetadataLines(
      APKModuleGraph<BuildTarget> apkModuleGraph,
      Function<BuildTarget, String> getFullyQualifiedName,
      Optional<ImmutableMultimap<APKModule, String>> moduleToClassesMap,
      Optional<ImmutableMultimap<APKModule, String>> apkModuleToNativeLibraryMap) {
    TreeMultimap<APKModule, String> orderedModuleToTargetsMap =
        TreeMultimap.create(Comparator.comparing(APKModule::getName), Ordering.natural());
    for (APKModule module : apkModuleGraph.getAPKModules()) {
      for (BuildTarget target : apkModuleGraph.getBuildTargets(module)) {
        orderedModuleToTargetsMap.put(module, getFullyQualifiedName.apply(target));
      }
    }

    // Module to module deps map is already sorted
    SortedMap<APKModule, ? extends SortedSet<APKModule>> moduleToDepsMap =
        apkModuleGraph.toOutgoingEdgesMap();

    // Write metadata lines to output
    LinkedList<String> metadataLines = new LinkedList<>();
    if (moduleToClassesMap.isPresent()) {
      // Get module to classes map in sorted order for build determinism and testing
      TreeMultimap<APKModule, String> orderedModuleToClassesMap =
          sortModuleToStringsMultimap(moduleToClassesMap.get());

      metadataLines.add(APKModuleMetadataUtil.CLASS_SECTION_HEADER);
      addModuleToStringsMultimap(orderedModuleToClassesMap, metadataLines);
    }
    metadataLines.add(APKModuleMetadataUtil.TARGETS_SECTION_HEADER);
    addModuleToStringsMultimap(orderedModuleToTargetsMap, metadataLines);
    if (apkModuleGraph.getDenyListModules().isPresent()) {
      metadataLines.add(APKModuleMetadataUtil.BLACKLIST_SECTION_HEADER);
      addBuildTargetList(
          apkModuleGraph.getDenyListModules().get(), metadataLines, getFullyQualifiedName);
    }
    metadataLines.add(APKModuleMetadataUtil.DEPS_SECTION_HEADER);
    addModuleToModulesMap(moduleToDepsMap, metadataLines);

    // Add libraries metadata
    if (apkModuleToNativeLibraryMap.isPresent()) {
      TreeMultimap<APKModule, String> orderedModuleToLibrariesMap =
          TreeMultimap.create(Comparator.comparing(APKModule::getName), Ordering.natural());
      orderedModuleToLibrariesMap.putAll(apkModuleToNativeLibraryMap.get());
      metadataLines.add(APKModuleMetadataUtil.LIBRARIES_SECTION_HEADER);
      addModuleToStringsMultimap(orderedModuleToLibrariesMap, metadataLines);
    }

    return metadataLines;
  }

  private static TreeMultimap<APKModule, String> sortModuleToStringsMultimap(
      ImmutableMultimap<APKModule, String> multimap) {
    TreeMultimap<APKModule, String> orderedMap =
        TreeMultimap.create(Comparator.comparing(APKModule::getName), Ordering.natural());
    orderedMap.putAll(multimap);
    return orderedMap;
  }

  private static void addModuleToStringsMultimap(
      Multimap<APKModule, String> map, Collection<String> dest) {
    for (APKModule dexStore : map.keySet()) {
      dest.add(APKModuleMetadataUtil.MODULE_INDENTATION + dexStore.getName());
      for (String item : map.get(dexStore)) {
        dest.add(APKModuleMetadataUtil.ITEM_INDENTATION + item);
      }
    }
  }

  private static void addModuleToModulesMap(
      Map<APKModule, ? extends Iterable<APKModule>> map, Collection<String> dest) {
    for (Map.Entry<APKModule, ? extends Iterable<APKModule>> entry : map.entrySet()) {
      dest.add(APKModuleMetadataUtil.MODULE_INDENTATION + entry.getKey().getName());
      for (APKModule item : entry.getValue()) {
        dest.add(APKModuleMetadataUtil.ITEM_INDENTATION + item.getName());
      }
    }
  }

  private static <BuildTarget extends Comparable<BuildTarget>> void addBuildTargetList(
      List<BuildTarget> buildTargets,
      Collection<String> dest,
      Function<BuildTarget, String> getFullyQualifiedName) {
    for (BuildTarget target : buildTargets) {
      dest.add(APKModuleMetadataUtil.MODULE_INDENTATION + getFullyQualifiedName.apply(target));
    }
  }
}
