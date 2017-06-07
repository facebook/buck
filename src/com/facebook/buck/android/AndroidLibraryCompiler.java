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

package com.facebook.buck.android;

import com.facebook.buck.android.AndroidLibraryDescription.JvmLanguage;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableCollection;
import java.nio.file.Path;

/**
 * Jvm compiler abstraction for android. Implementations of this class are used in {@link
 * AndroidLibraryDescription} to provide the actual compilation step. This allows us to use
 * different compilers for different {@link JvmLanguage}.
 */
public abstract class AndroidLibraryCompiler
    implements ImplicitDepsInferringDescription<AndroidLibraryDescription.CoreArg> {

  public static final Function<BuildContext, Iterable<Path>> ANDROID_CLASSPATH_FROM_CONTEXT =
      context -> context.getAndroidPlatformTargetSupplier().get().getBootclasspathEntries();

  public abstract CompileToJarStepFactory compileToJar(
      AndroidLibraryDescription.CoreArg args,
      JavacOptions javacOptions,
      BuildRuleResolver resolver);

  public boolean trackClassUsage(JavacOptions javacOptions) {
    return javacOptions.trackClassUsage();
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AndroidLibraryDescription.CoreArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {}
}
