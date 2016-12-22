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

package com.facebook.buck.jvm.java;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.graph.AbstractBreadthFirstThrowingTraversal;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Common utilities for working with {@link JavaLibrary} objects.
 */
public class JavaLibraryRules {

  /** Utility class: do not instantiate. */
  private JavaLibraryRules() {}

  static void addAccumulateClassNamesStep(
      JavaLibrary javaLibrary,
      BuildableContext buildableContext,
      ImmutableList.Builder<Step> steps) {

    Path pathToClassHashes = JavaLibraryRules.getPathToClassHashes(
        javaLibrary.getBuildTarget(), javaLibrary.getProjectFilesystem());
    steps.add(new MkdirStep(javaLibrary.getProjectFilesystem(), pathToClassHashes.getParent()));
    steps.add(
        new AccumulateClassNamesStep(
            javaLibrary.getProjectFilesystem(),
            Optional.ofNullable(javaLibrary.getPathToOutput()),
            pathToClassHashes));
    buildableContext.recordArtifact(pathToClassHashes);
  }

  static JavaLibrary.Data initializeFromDisk(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      OnDiskBuildInfo onDiskBuildInfo)
      throws IOException {
    List<String> lines =
        onDiskBuildInfo.getOutputFileContentsByLine(getPathToClassHashes(buildTarget, filesystem));
    ImmutableSortedMap<String, HashCode> classHashes = AccumulateClassNamesStep.parseClassHashes(
        lines);

    return new JavaLibrary.Data(classHashes);
  }

  private static Path getPathToClassHashes(BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, buildTarget, "%s.classes.txt");
  }

  /**
   * @return all the transitive native libraries a rule depends on, represented as
   *     a map from their system-specific library names to their {@link SourcePath} objects.
   */
  public static ImmutableMap<String, SourcePath> getNativeLibraries(
      Iterable<BuildRule> deps,
      final CxxPlatform cxxPlatform) throws NoSuchBuildTargetException {
    final ImmutableMap.Builder<String, SourcePath> libraries = ImmutableMap.builder();

    new AbstractBreadthFirstThrowingTraversal<BuildRule, NoSuchBuildTargetException>(deps) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) throws NoSuchBuildTargetException {
        if (rule instanceof NativeLinkable) {
          NativeLinkable linkable = (NativeLinkable) rule;
          libraries.putAll(linkable.getSharedLibraries(cxxPlatform));
        }
        if (rule instanceof NativeLinkable ||
            rule instanceof JavaLibrary) {
          return rule.getDeps();
        } else {
          return ImmutableSet.of();
        }
      }
    }.start();

    return libraries.build();
  }

  public static ImmutableSortedSet<SourcePath> getAbiInputs(
      BuildRuleResolver resolver,
      Iterable<BuildRule> inputs) throws NoSuchBuildTargetException {
    ImmutableSortedSet.Builder<SourcePath> abiRules =
        ImmutableSortedSet.naturalOrder();
    for (BuildRule dep : inputs) {
      if (dep instanceof HasJavaAbi) {
        Optional<BuildTarget> abiJarTarget = ((HasJavaAbi) dep).getAbiJar();
        if (abiJarTarget.isPresent()) {
          resolver.requireRule(abiJarTarget.get());
          abiRules.add(new BuildTargetSourcePath(abiJarTarget.get()));
        }
      }
    }
    return abiRules.build();
  }

}
