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

package com.facebook.buck.jvm.java;

import com.facebook.buck.cd.model.java.FilesystemParams;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import javax.annotation.Nullable;

/**
 * Interface for adding the steps for compiling source to producing a JAR, for JVM languages.
 *
 * @param <T> Type of extra parameters needed to create these steps.
 */
public interface CompileToJarStepFactory<T extends CompileToJarStepFactory.ExtraParams> {

  /**
   * Add the steps to {@code steps} to compile the sources (in {@code compilerParameters}) with java
   * compiler {@code resolvedJavac} for the build target {@code buildTargetValue} to a JAR, located
   * in {@code compilerOutputPathsValue}, relative to the filesystem root specified in {@code
   * filesystemParams}.
   *
   * <p>Language-specific parameters are passed through {@code extraParams}, which is instantiated
   * to a concrete type by implementations of this interface.
   */
  void createCompileToJarStep(
      FilesystemParams filesystemParams,
      BuildTargetValue buildTargetValue,
      CompilerOutputPathsValue compilerOutputPathsValue,
      CompilerParameters compilerParameters,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      ImmutableList.Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      ImmutableMap<RelPath, RelPath> resourcesMap,
      ResolvedJavac resolvedJavac,
      T extraParams);

  /** Upcasts {@code extraParams} to the type of parameter expected by this factory. */
  T castExtraParams(ExtraParams extraParams);

  /**
   * Extra params marker interface.
   *
   * <p>{@link CompileToJarStepFactory} is implemented for each JVM language: Java, Kotlin, Scala
   * and Groovy. Compiler daemons need to be able to create compile-to-jar steps without access to
   * an instance of {@link com.facebook.buck.core.build.context.BuildContext}, which is only
   * available in the main buck process. This class is used to conditionally allow the use of
   * BuildContext when creating steps for languages that do not support compiler daemons, and not
   * for others:
   *
   * <ul>
   *   <li>BuildContextAwareExtraParams, used for all languages that do not support compiler
   *       daemons.
   *   <li>JavaExtraParams (used for Java, contains no BuildContext)
   *   <li>KotlinExtraParams (used for Kotlin, contains no BuildContext)
   * </ul>
   */
  interface ExtraParams {}

  /**
   * Marker interface for implementations of {@link CompileToJarStepFactory}, indicating that they
   * can create the extra parameters necessary to create the compile steps "from scratch" (as
   * opposed to deserializing them).
   *
   * <p>For step factories that support compiler daemons, there will be a version of the factory
   * that can only create compile steps (used when deserializing commands in the daemon), and one
   * that can do both (used to create commands).
   */
  interface CreatesExtraParams<T extends CompileToJarStepFactory.ExtraParams> {
    T createExtraParams(BuildContext buildContext, AbsPath rootPath);
  }
}
