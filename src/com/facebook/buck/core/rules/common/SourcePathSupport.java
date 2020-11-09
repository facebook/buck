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

package com.facebook.buck.core.rules.common;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.sourcepath.ArchiveMemberSourcePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/** Utilities for operating on {@link SourcePath}s. */
public class SourcePathSupport {

  /**
   * Generates a map of {@link SourcePath} to {@link BuildTarget}. It guarantees (only if there is a
   * single call of this method per base target) that all {@link BuildTarget}s are unique, so can be
   * safely used in calls to {@link
   * com.facebook.buck.core.rules.ActionGraphBuilder#computeIfAbsent(BuildTarget, Function)}.
   * Guarantee is made by throwing an exception when there is a target name collision.
   *
   * <p>For example, given a PathSourcePath (e.g., "/Users/fb/repo/file.c"), a base target (e.g.,
   * "//A:B") and a prefix of "hash-", it will produce a mapping like "/Users/fb/repo/file.c" ->
   * "//A:B#hash-file.c.7a128b3d".
   */
  public static ImmutableBiMap<SourcePath, BuildTarget>
      generateAndCheckUniquenessOfBuildTargetsForSourcePaths(
          ImmutableSet<SourcePath> sourcePaths, BuildTarget baseTarget, String prefix) {
    ImmutableBiMap.Builder<SourcePath, BuildTarget> sourcePathToNameMap = ImmutableBiMap.builder();
    for (SourcePath sourcePath : sourcePaths) {
      sourcePathToNameMap.put(
          sourcePath,
          generateBuildTargetForSourcePathWithoutUniquenessCheck(
              sourcePath, baseTarget, prefix, false));
    }

    return sourcePathToNameMap.build();
  }

  /**
   * Unsafe method to generate a {@link BuildTarget} to use for {@link SourcePath} content hashing.
   * Uniqueness is not guaranteed, so the caller of the method should use it on their own risk (e.g.
   * there is no chance of target name collision if the caller can guarantee that only one file is
   * hashed from the generating target).
   */
  public static BuildTarget generateBuildTargetForSourcePathWithoutUniquenessCheck(
      SourcePath sourcePath, BuildTarget baseTarget, String prefix, boolean omitName) {
    String flavorName = generateFlavorNameForSourcePath(sourcePath, omitName);
    InternalFlavor flavor = InternalFlavor.of(String.format("%s%s", prefix, flavorName));
    return baseTarget.withAppendedFlavors(flavor);
  }

  /**
   * Generates a unique string that can be used as part of a flavor, based on the given SourcePath.
   * SourcePaths which are not equal are guaranteed to produce different strings.
   *
   * <p>For example, for a PathSourcePath (e.g., "/Users/fb/repo/file.c"), it will generate a string
   * like "file.c.7a128b3d".
   */
  private static String generateFlavorNameForSourcePath(
      SourcePath sourcePath, boolean omitFileName) {
    if (sourcePath instanceof PathSourcePath) {
      return generateFlavorName((PathSourcePath) sourcePath, omitFileName);
    }

    if (sourcePath instanceof ExplicitBuildTargetSourcePath) {
      return generateFlavorName((ExplicitBuildTargetSourcePath) sourcePath, omitFileName);
    }

    if (sourcePath instanceof DefaultBuildTargetSourcePath) {
      return generateFlavorName((DefaultBuildTargetSourcePath) sourcePath, omitFileName);
    }

    if (sourcePath instanceof ForwardingBuildTargetSourcePath) {
      return generateFlavorName((ForwardingBuildTargetSourcePath) sourcePath, omitFileName);
    }

    if (sourcePath instanceof ArchiveMemberSourcePath) {
      return generateFlavorName((ArchiveMemberSourcePath) sourcePath, omitFileName);
    }

    throw new RuntimeException(
        String.format(
            "Encountered unknown SourcePath subclass: %s", sourcePath.getClass().getName()));
  }

  private static String generateFlavorName(
      DefaultBuildTargetSourcePath sourcePath, boolean omitFileName) {
    return sanitizeBuildTargetWithOutputs(
        sourcePath.getTargetWithOutputs(), Optional.empty(), Optional.empty(), omitFileName);
  }

  private static String generateFlavorName(
      ForwardingBuildTargetSourcePath sourcePath, boolean omitFileName) {
    String delegateName = generateFlavorNameForSourcePath(sourcePath.getDelegate(), omitFileName);
    return sanitizeBuildTargetWithOutputs(
        sourcePath.getTargetWithOutputs(),
        Optional.empty(),
        Optional.of(delegateName),
        omitFileName);
  }

  private static String generateFlavorName(
      ExplicitBuildTargetSourcePath sourcePath, boolean omitFileName) {
    Path path = sourcePath.getResolvedPath();
    String fileName = path.getFileName().toString();
    return sanitizeBuildTargetWithOutputs(
        sourcePath.getTargetWithOutputs(),
        Optional.of(fileName),
        Optional.of(path.toString()),
        omitFileName);
  }

  private static String sanitizeBuildTargetWithOutputs(
      BuildTargetWithOutputs buildTargetWithOutputs,
      Optional<String> inputfileName,
      Optional<String> maybeFullNameSuffix,
      boolean omitFileName) {
    BuildTarget buildTarget = buildTargetWithOutputs.getBuildTarget();
    String fullyQualifiedNameWithOptionalOutputSuffix = buildTargetWithOutputs.toString();
    Optional<String> maybeFileName =
        omitFileName
            ? Optional.empty()
            : Optional.of(inputfileName.orElse(buildTarget.getShortName()));
    String fullName = fullyQualifiedNameWithOptionalOutputSuffix + maybeFullNameSuffix.orElse("");
    return sanitize(maybeFileName, fullName);
  }

  private static String generateFlavorName(
      ArchiveMemberSourcePath sourcePath, boolean omitFileName) {
    String archiveName =
        generateFlavorNameForSourcePath(sourcePath.getArchiveSourcePath(), omitFileName);
    Path memberPath = sourcePath.getMemberPath();
    Optional<String> maybeFileName =
        omitFileName ? Optional.empty() : Optional.of(memberPath.getFileName().toString());
    return sanitize(maybeFileName, archiveName + memberPath.toString());
  }

  private static String generateFlavorName(PathSourcePath sourcePath, boolean omitFileName) {
    Path path = sourcePath.getRelativePath();
    Optional<String> maybeFileName =
        omitFileName ? Optional.empty() : Optional.of(path.getFileName().toString());
    return sanitize(maybeFileName, path.toString());
  }

  private static String sanitize(Optional<String> fileName, String fullName) {
    // The hash prevents collisions when fileName is the same,
    // e.g., "an/example.c", "an_example.c" etc.
    return fileName.map(Flavor::replaceInvalidCharacters).map(f -> f + ".").orElse("")
        + Hashing.murmur3_32().hashString(fullName, StandardCharsets.UTF_8);
  }
}
