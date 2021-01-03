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

package com.facebook.buck.jvm.groovy;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.java.BuildContextAwareExtraParams;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.CompilerOutputPathsValue;
import com.facebook.buck.jvm.java.CompilerParameters;
import com.facebook.buck.jvm.java.FilesystemParams;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.ResolvedJavac;
import com.facebook.buck.jvm.java.ResolvedJavacOptions;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;

/** Factory that creates Groovy related compile build steps. */
class GroovyToJarStepFactory extends CompileToJarStepFactory<BuildContextAwareExtraParams> {

  @AddToRuleKey private final Tool groovyc;
  @AddToRuleKey private final Optional<ImmutableList<String>> extraArguments;
  @AddToRuleKey private final JavacOptions javacOptions;

  public GroovyToJarStepFactory(
      Tool groovyc,
      Optional<ImmutableList<String>> extraArguments,
      JavacOptions javacOptions,
      boolean withDownwardApi) {
    super(CompileToJarStepFactory.hasAnnotationProcessing(javacOptions), withDownwardApi);
    this.javacOptions = javacOptions;
    this.groovyc = groovyc;
    this.extraArguments = extraArguments;
  }

  @Override
  public void createCompileStep(
      FilesystemParams filesystemParams,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      BuildTargetValue invokingRule,
      CompilerOutputPathsValue compilerOutputPathsValue,
      CompilerParameters parameters,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      ResolvedJavac resolvedJavac,
      BuildContextAwareExtraParams extraParams) {
    AbsPath rootPath = filesystemParams.getRootPath();

    ImmutableSortedSet<RelPath> declaredClasspathEntries = parameters.getClasspathEntries();
    ImmutableSortedSet<RelPath> sourceFilePaths = parameters.getSourceFilePaths();
    RelPath outputDirectory = parameters.getOutputPaths().getClassesDir();
    Path pathToSrcsList = parameters.getOutputPaths().getPathToSourcesList().getPath();

    SourcePathResolverAdapter sourcePathResolver =
        extraParams.getBuildContext().getSourcePathResolver();
    ImmutableList<String> commandPrefix = groovyc.getCommandPrefix(sourcePathResolver);

    steps.add(
        new GroovycStep(
            commandPrefix,
            extraArguments,
            ResolvedJavacOptions.of(javacOptions, sourcePathResolver, rootPath),
            outputDirectory.getPath(),
            sourceFilePaths,
            pathToSrcsList,
            declaredClasspathEntries,
            withDownwardApi));
  }
}
