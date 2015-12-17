/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class JavaLibraryDescription implements JvmLibraryConfigurable<JavaLibraryDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("java_library");

  @VisibleForTesting
  final JavacOptions defaultOptions;

  public JavaLibraryDescription(JavacOptions defaultOptions) {
    this.defaultOptions = defaultOptions;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public JvmLibraryConfiguration createConfiguration(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      JavaLibraryDescription.Arg args) {
    JavacOptions javacOptions = JavacOptionsFactory.create(
        defaultOptions,
        params,
        resolver,
        pathResolver,
        args
    );

    Optional<Path> generatedSourceFolderName = javacOptions.getGeneratedSourceFolderName();
    CompileToJarStepFactory compileStepFactory =
        new JavacToJarStepFactory(javacOptions, JavacOptionsAmender.IDENTITY);

    addGwtModule(resolver, pathResolver, params, args);

    return new JvmLibraryConfiguration(
        compileStepFactory,
        generatedSourceFolderName,
        pathResolver.filterBuildRuleInputs(javacOptions.getInputs(pathResolver))
    );
  }

  /**
   * Creates a {@link BuildRule} with the {@link JavaLibrary#GWT_MODULE_FLAVOR}, if appropriate.
   * <p>
   * If {@code arg.srcs} or {@code arg.resources} is non-empty, then the return value will not be
   * absent.
   */
  private static void addGwtModule(
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      BuildRuleParams javaLibraryParams,
      Arg arg) {
    BuildTarget libraryTarget = javaLibraryParams.getBuildTarget();

    if (arg.srcs.get().isEmpty() &&
        arg.resources.get().isEmpty() &&
        !libraryTarget.isFlavored()) {
      return;
    }

    BuildTarget gwtModuleBuildTarget = BuildTarget.of(
        libraryTarget.getUnflavoredBuildTarget(),
        ImmutableSet.of(JavaLibrary.GWT_MODULE_FLAVOR));
    ImmutableSortedSet<SourcePath> filesForGwtModule = ImmutableSortedSet
        .<SourcePath>naturalOrder()
        .addAll(arg.srcs.get())
        .addAll(arg.resources.get())
        .build();

    // If any of the srcs or resources are BuildTargetSourcePaths, then their respective BuildRules
    // must be included as deps.
    ImmutableSortedSet<BuildRule> deps =
        ImmutableSortedSet.copyOf(pathResolver.filterBuildRuleInputs(filesForGwtModule));
    GwtModule gwtModule = new GwtModule(
        javaLibraryParams.copyWithChanges(
            gwtModuleBuildTarget,
            Suppliers.ofInstance(deps),
            /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        pathResolver,
        filesForGwtModule);
    resolver.addToIndex(gwtModule);
  }

  public static class Arg extends JvmLibraryDescription.Arg { }
}
