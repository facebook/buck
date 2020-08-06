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

import com.facebook.buck.core.model.targetgraph.TargetNode;
import java.util.Optional;

/** Interface for building {@link IjLibrary} objects from {@link TargetNode}s. */
public abstract class IjLibraryFactory {
  // This is the name hardcoded in the Kotlin plugin
  private static final String KOTLIN_JAVA_RUNTIME_LIBRARY_NAME = "KotlinJavaRuntime";

  private static class KotlinJavaRuntimeLibraryHolder {
    private static final IjLibrary INSTANCE =
        IjLibrary.builder()
            .setName(KOTLIN_JAVA_RUNTIME_LIBRARY_NAME)
            .setType(IjLibrary.Type.KOTLIN_JAVA_RUNTIME)
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

  public static IjLibrary getKotlinJavaRuntimeLibrary() {
    return KotlinJavaRuntimeLibraryHolder.INSTANCE;
  }
}
