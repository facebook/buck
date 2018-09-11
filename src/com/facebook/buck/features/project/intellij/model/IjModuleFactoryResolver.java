/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.features.project.intellij.model;

import com.facebook.buck.android.AndroidBinaryDescriptionArg;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidResourceDescriptionArg;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import java.nio.file.Path;
import java.util.Optional;

/** Provides the {@link IjModuleFactory} with {@link Path}s to various elements of the project. */
public interface IjModuleFactoryResolver {
  /**
   * @param targetNode node to generate the path to
   * @return the project-relative path to a directory structure under which the R.class file can be
   *     found (the structure will be the same as the package path of the R class). A path should be
   *     returned only if the given TargetNode requires the R.class to compile.
   */
  Optional<Path> getDummyRDotJavaPath(TargetNode<?> targetNode);

  /**
   * @param targetNode node describing the Android binary to get the manifest of.
   * @return path on disk to the AndroidManifest.
   */
  Path getAndroidManifestPath(TargetNode<AndroidBinaryDescriptionArg> targetNode);

  /**
   * @param targetNode node describing the Android library to get the manifest of.
   * @return path on disk to the AndroidManifest.
   */
  Optional<Path> getLibraryAndroidManifestPath(
      TargetNode<AndroidLibraryDescription.CoreArg> targetNode);

  /**
   * @param targetNode node describing the Android binary to get the Proguard config of.
   * @return path on disk to the proguard config.
   */
  Optional<Path> getProguardConfigPath(TargetNode<AndroidBinaryDescriptionArg> targetNode);

  /**
   * @param targetNode node describing the Android resources to get the path of.
   * @return path on disk to the resources folder.
   */
  Optional<Path> getAndroidResourcePath(TargetNode<AndroidResourceDescriptionArg> targetNode);

  /**
   * @param targetNode node describing the Android assets to get the path of.
   * @return path on disk to the assets folder.
   */
  Optional<Path> getAssetsPath(TargetNode<AndroidResourceDescriptionArg> targetNode);

  /**
   * @param targetNode node which may use annotation processors.
   * @return path to the annotation processor output if any annotation proceessors are configured
   *     for the given node.
   */
  Optional<Path> getAnnotationOutputPath(TargetNode<? extends JvmLibraryArg> targetNode);

  /**
   * @param targetNode node which may use annotation processors.
   * @return path to the annotation processor output of the associated abi target if any annotation
   *     proceessors are configured for the given node.
   */
  Optional<Path> getAbiAnnotationOutputPath(TargetNode<? extends JvmLibraryArg> targetNode);

  /**
   * @param targetNode node which may specify it's own compiler output path.
   * @return path to the classes that make up the compiler output.
   */
  Optional<Path> getCompilerOutputPath(TargetNode<? extends JvmLibraryArg> targetNode);
}
