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

package com.facebook.buck.android.resources;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.IntStream;

public class UsedResourcesFinder {
  interface ApkContentProvider {
    ResourceTable getResourceTable();

    ResourcesXml getXml(String path);

    boolean hasFile(String path);
  }

  public static ResourceClosure computeClosure(
      ApkContentProvider apkContentProvider,
      Iterable<String> rootFiles,
      Iterable<Integer> rootIds) {
    State state = new State(apkContentProvider, rootFiles, rootIds);
    state.process();
    return new ResourceClosure(
        state.processedFiles.stream()
            .filter(apkContentProvider::hasFile)
            .collect(ImmutableSet.toImmutableSet()),
        state.processedIds);
  }

  public static ResourceClosure computePrimaryApkClosure(ApkContentProvider apkContentProvider) {
    // The Android framework (and other apps) have easy access to the values in the manifest and
    // anything reachable from there. Also, the framework needs access to animation references to
    // drive some animations (requested by the app itself).
    ImmutableList.Builder<Integer> rootIds = ImmutableList.builder();
    ResTablePackage resPackage = apkContentProvider.getResourceTable().getPackage();
    for (ResTableTypeSpec spec : resPackage.getTypeSpecs()) {
      if (spec.getResourceTypeName(resPackage).equals("anim")) {
        int startId = (ResTablePackage.APP_PACKAGE_ID << 24) | (spec.getResourceType() << 16);
        rootIds.addAll(IntStream.range(startId, startId + spec.getEntryCount())::iterator);
      }
    }
    return computeClosure(
        apkContentProvider, ImmutableList.of("AndroidManifest.xml"), rootIds.build());
  }

  public static class ResourceClosure {
    final Set<String> files;
    final Map<Integer, SortedSet<Integer>> idsByType;

    public ResourceClosure(Set<String> files, Map<Integer, SortedSet<Integer>> idsByType) {
      this.files = files;
      this.idsByType = idsByType;
    }
  }

  private UsedResourcesFinder() {}

  private static class State {
    final ApkContentProvider apkContent;
    final Map<Integer, SortedSet<Integer>> processedIds;
    final Map<Integer, SortedSet<Integer>> idsToProcess;
    final List<String> xmlToProcess;
    final Set<String> processedFiles;

    public State(
        ApkContentProvider apkContentProvider,
        Iterable<String> rootFiles,
        Iterable<Integer> rootIds) {
      this.apkContent = apkContentProvider;
      processedIds = new HashMap<>();
      idsToProcess = new HashMap<>();
      processedFiles = new HashSet<>();
      xmlToProcess = new ArrayList<>();

      rootFiles.forEach(this::addPossibleFileToExtract);
      rootIds.forEach(this::addIdToProcess);
    }

    void process() {
      while (!idsToProcess.isEmpty() || !xmlToProcess.isEmpty()) {
        processXml();
        processIds();
      }
    }

    void addIdToProcess(int id) {
      int pkg = id >> 24;
      if (pkg == ResTablePackage.APP_PACKAGE_ID) {
        int type = (id >> 16) & 0xFF;
        int k = id & 0xFFFF;
        Set<Integer> processedIdsForType =
            Objects.requireNonNull(processedIds.computeIfAbsent(type, v -> new TreeSet<>()));
        if (!processedIdsForType.contains(k)) {
          processedIdsForType.add(k);
          Objects.requireNonNull(idsToProcess.computeIfAbsent(type, v -> new TreeSet<>())).add(k);
        }
      }
    }

    void processIds() {
      ImmutableMap<Integer, SortedSet<Integer>> ids = ImmutableMap.copyOf(idsToProcess);
      idsToProcess.clear();
      iterateArsc(ids);
    }

    void iterateArsc(ImmutableMap<Integer, SortedSet<Integer>> ids) {
      ResTablePackage resPackage = apkContent.getResourceTable().getPackage();
      StringPool strings = apkContent.getResourceTable().getStrings();
      ids.forEach(
          (k, v) -> {
            ResTableTypeSpec spec = resPackage.getTypeSpec(k);
            String resourceTypeName = spec.getResourceTypeName(resPackage);
            int[] idsToVisit = v.stream().mapToInt(i -> i).toArray();
            spec.visitReferences(idsToVisit, this::addIdToProcess);
            if (!resourceTypeName.equals("string") && !resourceTypeName.equals("id")) {
              spec.visitStringReferences(
                  idsToVisit,
                  (stringRef) -> addPossibleFileToExtract(strings.getString(stringRef)));
            }
          });
    }

    private void addPossibleFileToExtract(String val) {
      if (!processedFiles.contains(val)) {
        processedFiles.add(val);
        if (val.endsWith(".xml") && apkContent.hasFile(val)) {
          xmlToProcess.add(val);
        }
      }
    }

    void processXml() {
      // This doesn't add new xml to process.
      xmlToProcess.forEach(s -> apkContent.getXml(s).visitReferences(this::addIdToProcess));
      xmlToProcess.clear();
    }
  }
}
