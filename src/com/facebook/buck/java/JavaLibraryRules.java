/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.java;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Common utilities for working with {@link JavaLibrary} objects.
 */
public class JavaLibraryRules {

  /** Utility class: do not instantiate. */
  private JavaLibraryRules() {}

  static void addAccumulateClassNamesStep(JavaLibrary javaLibrary,
      BuildableContext buildableContext,
      ImmutableList.Builder<Step> steps) {

    Path pathToClassHashes = JavaLibraryRules.getPathToClassHashes(
        javaLibrary.getBuildTarget());
    steps.add(new MkdirStep(pathToClassHashes.getParent()));
    steps.add(new AccumulateClassNamesStep(
        Optional.fromNullable(javaLibrary.getPathToOutput()),
        pathToClassHashes));
    buildableContext.recordArtifact(pathToClassHashes);
  }

  static JavaLibrary.Data initializeFromDisk(
      BuildTarget buildTarget,
      OnDiskBuildInfo onDiskBuildInfo)
      throws IOException {
    Optional<Sha1HashCode> abiKeyHash = onDiskBuildInfo.getHash(AbiRule.ABI_KEY_ON_DISK_METADATA);
    if (!abiKeyHash.isPresent()) {
      throw new IllegalStateException(String.format(
          "Should not be initializing %s from disk if the ABI key is not written.",
          buildTarget));
    }

    List<String> lines =
        onDiskBuildInfo.getOutputFileContentsByLine(getPathToClassHashes(buildTarget));
    ImmutableSortedMap<String, HashCode> classHashes = AccumulateClassNamesStep.parseClassHashes(
        lines);

    return new JavaLibrary.Data(abiKeyHash.get(), classHashes);
  }

  private static Path getPathToClassHashes(BuildTarget buildTarget) {
    return BuildTargets.getGenPath(buildTarget, "%s.classes.txt");
  }

  /**
   * @return all the transitive native libraries a rule depends on, represented as
   *     a map from their system-specific library names to their {@link SourcePath} objects.
   */
  public static ImmutableMap<String, SourcePath> getNativeLibraries(
      Iterable<BuildRule> deps,
      final CxxPlatform cxxPlatform) {
    final ImmutableMap.Builder<String, SourcePath> libraries = ImmutableMap.builder();

    new AbstractBreadthFirstTraversal<BuildRule>(deps) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        if (rule instanceof JavaNativeLinkable) {
          JavaNativeLinkable linkable = (JavaNativeLinkable) rule;
          libraries.putAll(linkable.getSharedLibraries(cxxPlatform));
        }
        if (rule instanceof JavaNativeLinkable ||
            rule instanceof JavaLibrary) {
          return rule.getDeps();
        } else {
          return ImmutableSet.of();
        }
      }
    }.start();

    return libraries.build();
  }
}
