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

package com.facebook.buck.features.project.intellij.model;

import com.facebook.buck.android.AndroidLibraryGraphEnhancer;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.features.project.intellij.Util;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Interface for building {@link IjLibrary} objects from {@link TargetNode}s. */
public abstract class IjLibraryFactory {
  private final Set<String> uniqueLibraryNamesSet = new HashSet<>();

  /** Rule describing how to create a {@link IjLibrary} from a {@link TargetNode}. */
  protected interface IjLibraryRule {
    void applyRule(TargetNode<?> targetNode, IjLibrary.Builder library);
  }

  // This is the name hardcoded in the Kotlin plugin
  private static final String KOTLIN_JAVA_RUNTIME_LIBRARY_NAME = "KotlinJavaRuntime";

  private static class KotlinJavaRuntimeLibraryHolder {
    private static final IjLibrary INSTANCE =
        IjLibrary.builder()
            .setName(KOTLIN_JAVA_RUNTIME_LIBRARY_NAME)
            .setType(IjLibrary.Type.KOTLIN_JAVA_RUNTIME)
            .setLevel(IjLibrary.Level.PROJECT)
            .build();
  }

  /**
   * Creates an IjLibrary.
   *
   * @param target target to create it from.
   * @return if the target is of a type that can be mapped to an {@link IjLibrary} (Jar/Aar) or if
   *     the target's output is a .jar an instance is returned.
   */
  public abstract Optional<IjLibrary> getLibrary(TargetNode<?> target);

  protected IjLibrary createLibrary(TargetNode<?> targetNode, IjLibraryRule rule) {
    String libraryName = Util.intelliJLibraryName(targetNode.getBuildTarget());
    Preconditions.checkState(
        uniqueLibraryNamesSet.add(libraryName),
        "Trying to use the same library name for different targets.");

    IjLibrary.Builder libraryBuilder =
        IjLibrary.builder()
            .setName(libraryName)
            .setType(IjLibrary.Type.DEFAULT)
            .setLevel(IjLibrary.Level.PROJECT)
            .setTargets(ImmutableSet.of(targetNode.getBuildTarget()));
    rule.applyRule(targetNode, libraryBuilder);
    return libraryBuilder.build();
  }

  public static IjLibrary getKotlinJavaRuntimeLibrary() {
    return KotlinJavaRuntimeLibraryHolder.INSTANCE;
  }

  public static IjLibrary createDummyRDotJavaLibrary(
      BuildTarget target, Path dummyRDotJavaClassPath, boolean isModuleLibraryEnabled) {
    BuildTarget dummyRDotJavaTarget =
        target.withFlavors(AndroidLibraryGraphEnhancer.DUMMY_R_DOT_JAVA_FLAVOR);
    return IjLibrary.builder()
        .setName(Util.intelliJLibraryName(dummyRDotJavaTarget))
        .setType(IjLibrary.Type.DEFAULT)
        .setLevel(isModuleLibraryEnabled ? IjLibrary.Level.MODULE : IjLibrary.Level.PROJECT)
        .setBinaryJars(ImmutableSortedSet.of(dummyRDotJavaClassPath))
        .setTargets(ImmutableSet.of(dummyRDotJavaTarget))
        .build();
  }
}
