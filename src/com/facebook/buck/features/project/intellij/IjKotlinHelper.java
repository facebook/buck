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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidLibraryDescriptionArg;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.features.project.intellij.model.IjLibraryFactory;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import com.facebook.buck.jvm.kotlin.KotlinLibraryDescription;
import com.facebook.buck.jvm.kotlin.KotlinLibraryDescriptionArg;
import com.facebook.buck.jvm.kotlin.KotlinTestDescriptionArg;

/** Helper class related to Kotlin */
public final class IjKotlinHelper {
  private IjKotlinHelper() {}

  /** Whether the target node associated with the constructorArg will produce a Kotlin module */
  public static boolean isKotlinModule(JvmLibraryArg constructorArg) {
    return constructorArg instanceof KotlinLibraryDescriptionArg
        || constructorArg instanceof KotlinTestDescriptionArg
        || constructorArg instanceof AndroidLibraryDescriptionArg
            && ((AndroidLibraryDescriptionArg) constructorArg)
                .getLanguage()
                .map(AndroidLibraryDescription.JvmLanguage.KOTLIN::equals)
                .orElse(false);
  }

  /** Whether the target node associated with the constructorArg requires kapt */
  public static boolean requiresKapt(JvmLibraryArg constructorArg) {
    return (!constructorArg.getPlugins().isEmpty()
            || !constructorArg.getAnnotationProcessors().isEmpty())
        && constructorArg instanceof KotlinLibraryDescription.CoreArg
        && ((KotlinLibraryDescription.CoreArg) constructorArg)
            .getAnnotationProcessingTool()
            .map(KotlinLibraryDescription.AnnotationProcessingTool.KAPT::equals)
            .orElse(true);
  }

  /** Add KotlinJavaRuntime library dependency to a module if necessary */
  public static void addKotlinJavaRuntimeLibraryDependencyIfNecessary(
      TargetNode<? extends JvmLibraryArg> target, ModuleBuildContext context) {
    if (isKotlinModule(target.getConstructorArg())) {
      context.addExtraLibraryDependency(IjLibraryFactory.getKotlinJavaRuntimeLibrary());
    }
  }
}
