/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.jvm.java.JarParameters;
import com.facebook.buck.jvm.java.RemoveClassesPatternsMatcher;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.function.Predicate;

/** {@link JarParameters} to protobuf serializer */
public class JarParametersSerializer {

  private JarParametersSerializer() {}

  /**
   * Serializes {@link JarParameters} into javacd model's {@link
   * com.facebook.buck.javacd.model.JarParameters}.
   */
  public static com.facebook.buck.javacd.model.JarParameters serialize(
      JarParameters jarParameters) {
    com.facebook.buck.javacd.model.JarParameters.Builder builder =
        com.facebook.buck.javacd.model.JarParameters.newBuilder();

    builder.setHashEntries(jarParameters.getHashEntries());
    builder.setMergeManifests(jarParameters.getMergeManifests());
    builder.setDisallowAllDuplicates(jarParameters.getDisallowAllDuplicates());
    builder.setJarPath(RelPathSerializer.serialize(jarParameters.getJarPath()));
    Predicate<Object> removeEntryPredicate = jarParameters.getRemoveEntryPredicate();
    Preconditions.checkState(removeEntryPredicate instanceof RemoveClassesPatternsMatcher);
    builder.setRemoveEntryPredicate(
        RemoveClassesPatternsMatcherSerializer.serialize(
            (RemoveClassesPatternsMatcher) removeEntryPredicate));
    for (RelPath entry : jarParameters.getEntriesToJar()) {
      builder.addEntriesToJar(RelPathSerializer.serialize(entry));
    }
    for (RelPath entry : jarParameters.getOverrideEntriesToJar()) {
      builder.addOverrideEntriesToJar(RelPathSerializer.serialize(entry));
    }

    Optional<String> mainClass = jarParameters.getMainClass();
    mainClass.ifPresent(builder::setMainClass);

    Optional<RelPath> manifestFile = jarParameters.getManifestFile();
    manifestFile.map(RelPathSerializer::serialize).ifPresent(builder::setManifestFile);

    builder.setDuplicatesLogLevel(
        LogLevelSerializer.serialize(jarParameters.getDuplicatesLogLevel()));

    return builder.build();
  }

  /**
   * Deserializes javacd model's {@link com.facebook.buck.javacd.model.JarParameters} into {@link
   * JarParameters}.
   */
  public static JarParameters deserialize(
      com.facebook.buck.javacd.model.JarParameters jarParameters) {
    JarParameters.Builder builder = JarParameters.builder();

    builder.setHashEntries(jarParameters.getHashEntries());
    builder.setMergeManifests(jarParameters.getMergeManifests());
    builder.setDisallowAllDuplicates(jarParameters.getDisallowAllDuplicates());
    builder.setJarPath(RelPathSerializer.deserialize(jarParameters.getJarPath()));
    builder.setRemoveEntryPredicate(
        RemoveClassesPatternsMatcherSerializer.deserialize(
            jarParameters.getRemoveEntryPredicate()));
    builder.setEntriesToJar(
        jarParameters.getEntriesToJarList().stream()
            .map(RelPathSerializer::deserialize)
            .collect(ImmutableSortedSet.toImmutableSortedSet(RelPath.comparator())));
    builder.setOverrideEntriesToJar(
        jarParameters.getOverrideEntriesToJarList().stream()
            .map(RelPathSerializer::deserialize)
            .collect(ImmutableSortedSet.toImmutableSortedSet(RelPath.comparator())));

    String mainClass = jarParameters.getMainClass();
    if (!mainClass.isEmpty()) {
      builder.setMainClass(mainClass);
    }
    builder.setManifestFile(
        jarParameters.hasManifestFile()
            ? Optional.of(RelPathSerializer.deserialize(jarParameters.getManifestFile()))
            : Optional.empty());

    builder.setDuplicatesLogLevel(
        LogLevelSerializer.deserialize(jarParameters.getDuplicatesLogLevel()));

    return builder.build();
  }
}
