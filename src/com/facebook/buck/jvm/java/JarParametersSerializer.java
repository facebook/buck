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

package com.facebook.buck.jvm.java;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public class JarParametersSerializer {
  private JarParametersSerializer() {}

  private static final String OUTPUT_PATH = "output_path";
  private static final String CLASSES_TO_REMOVE = "classes_to_remove";
  private static final String CLASSES_TO_REMOVE_PATTERN = "pattern";
  private static final String CLASSES_TO_REMOVE_PATTERN_FLAGS = "flags";
  private static final String ENTRIES = "entries";
  private static final String MAIN_CLASS = "main_class";
  private static final String MANIFEST_FILE = "manifest_file";
  private static final String MERGE_MANIFESTS = "merge_manifests";
  private static final String HASH_ENTRIES = "hash_entries";

  public static ImmutableMap<String, Object> serialize(JarParameters settings) {
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();

    builder.put(OUTPUT_PATH, settings.getJarPath().toString());

    ImmutableList.Builder<ImmutableMap<String, Object>> serializedPatterns =
        ImmutableList.builder();
    for (Pattern pattern :
        ((RemoveClassesPatternsMatcher) settings.getRemoveEntryPredicate()).getPatterns()) {
      serializedPatterns.add(
          ImmutableMap.<String, Object>of(
              CLASSES_TO_REMOVE_PATTERN, pattern.pattern(),
              CLASSES_TO_REMOVE_PATTERN_FLAGS, pattern.flags()));
    }
    builder.put(CLASSES_TO_REMOVE, serializedPatterns.build());

    ImmutableList.Builder<String> serializedEntries = ImmutableList.builder();
    for (Path entry : settings.getEntriesToJar()) {
      serializedEntries.add(entry.toString());
    }
    builder.put(ENTRIES, serializedEntries.build());

    if (settings.getMainClass().isPresent()) {
      builder.put(MAIN_CLASS, settings.getMainClass().get());
    }

    if (settings.getManifestFile().isPresent()) {
      builder.put(MANIFEST_FILE, settings.getManifestFile().get().toString());
    }

    builder.put(MERGE_MANIFESTS, settings.getMergeManifests());
    builder.put(HASH_ENTRIES, settings.getHashEntries());

    return builder.build();
  }

  @SuppressWarnings("unchecked")
  public static JarParameters deserialize(Map<String, Object> data) {
    Path outputPath = Paths.get((String) Preconditions.checkNotNull(data.get(OUTPUT_PATH)));

    ImmutableSet.Builder<Pattern> classesToRemove = ImmutableSet.builder();
    for (Map<String, Object> patternData :
        (List<Map<String, Object>>) Preconditions.checkNotNull(data.get(CLASSES_TO_REMOVE))) {
      classesToRemove.add(
          Pattern.compile(
              (String)
                  Preconditions.checkNotNull(
                      patternData.get(CLASSES_TO_REMOVE_PATTERN),
                      "Didn't find classes to remove pattern in serialized JarParameters"),
              (int)
                  Preconditions.checkNotNull(
                      patternData.get(CLASSES_TO_REMOVE_PATTERN_FLAGS),
                      "Didn't find flags in serialized JarParameters")));
    }

    ImmutableSortedSet.Builder<Path> entries = ImmutableSortedSet.naturalOrder();
    for (String entry : (List<String>) Preconditions.checkNotNull(data.get(ENTRIES))) {
      entries.add(Paths.get(entry));
    }

    Optional<String> mainClass = Optional.empty();
    if (data.containsKey(MAIN_CLASS)) {
      mainClass = Optional.of((String) data.get(MAIN_CLASS));
    }

    Optional<Path> manifestFile = Optional.empty();
    if (data.containsKey(MANIFEST_FILE)) {
      manifestFile = Optional.of(Paths.get((String) data.get(MANIFEST_FILE)));
    }

    JarParameters.Builder resultBuilder =
        JarParameters.builder()
            .setJarPath(outputPath)
            .setRemoveEntryPredicate(new RemoveClassesPatternsMatcher(classesToRemove.build()))
            .setEntriesToJar(entries.build())
            .setMainClass(mainClass)
            .setManifestFile(manifestFile);

    if (data.containsKey(MERGE_MANIFESTS)) {
      resultBuilder.setMergeManifests((boolean) data.get(MERGE_MANIFESTS));
    }

    if (data.containsKey(HASH_ENTRIES)) {
      resultBuilder.setHashEntries((boolean) data.get(HASH_ENTRIES));
    }

    return resultBuilder.build();
  }
}
