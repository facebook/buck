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

package com.facebook.buck.jvm.java;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.collect.ImmutableCollection;
import javax.annotation.Nullable;

public abstract class ConfiguredCompilerFactory {

  // TODO(jkeljo): args is not actually Nullable in all subclasses, but it is also not
  // straightforward to create a safe "empty" default value. Find a fix.
  public abstract ConfiguredCompiler configure(
      SourcePathResolver sourcePathResolver,
      SourcePathRuleFinder ruleFinder,
      ProjectFilesystem projectFilesystem,
      @Nullable JvmLibraryArg args,
      JavacOptions javacOptions,
      BuildRuleResolver buildRuleResolver);

  public boolean trackClassUsage(@SuppressWarnings("unused") JavacOptions javacOptions) {
    return false;
  }

  public boolean shouldCompileAgainstAbis() {
    // Buck's ABI generation support was built for Java and hasn't been extended for other JVM
    // languages yet, so this is defaulted false.
    // See https://github.com/facebook/buck/issues/1386
    return false;
  }

  public boolean shouldGenerateSourceAbi() {
    return false;
  }

  public boolean shouldGenerateSourceOnlyAbi() {
    return false;
  }

  public boolean shouldMigrateToSourceOnlyAbi() {
    return false;
  }

  public void addTargetDeps(
      @SuppressWarnings("unused") ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      @SuppressWarnings("unused")
          ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {}
}
