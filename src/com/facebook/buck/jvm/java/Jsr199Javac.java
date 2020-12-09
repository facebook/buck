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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.jvm.java.abi.source.api.SourceOnlyAbiRuleInfoFactory;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import javax.annotation.Nullable;
import javax.tools.JavaCompiler;

/** Command used to compile java libraries with a variety of ways to handle dependencies. */
public abstract class Jsr199Javac implements Javac {

  @Override
  public final ResolvedJsr199Javac resolve(SourcePathResolverAdapter resolver) {
    return create(resolver);
  }

  protected abstract ResolvedJsr199Javac create(SourcePathResolverAdapter resolver);

  /** Base class for a resolved javac in-process tool. */
  public abstract static class ResolvedJsr199Javac implements ResolvedJavac {

    protected abstract JavaCompiler createCompiler(JavacExecutionContext context);

    @Override
    public String getDescription(
        ImmutableList<String> options,
        ImmutableSortedSet<Path> javaSourceFilePaths,
        RelPath pathToSrcsList) {
      StringBuilder builder = new StringBuilder("javac ");
      Joiner.on(" ").appendTo(builder, options);
      builder.append(" ");
      builder.append("@").append(pathToSrcsList);

      return builder.toString();
    }

    @Override
    public String getShortName() {
      return "javac";
    }

    @Override
    public ResolvedJavac.Invocation newBuildInvocation(
        JavacExecutionContext context,
        BuildTargetValue invokingRule,
        RelPath libraryOutputJarDirPath,
        RelPath sourceAbiOutputJarDirPath,
        RelPath sourceOnlyAbiOutputJarDirPath,
        ImmutableList<String> options,
        ImmutableList<JavacPluginJsr199Fields> annotationProcessors,
        ImmutableList<JavacPluginJsr199Fields> javacPlugins,
        ImmutableSortedSet<Path> javaSourceFilePaths,
        RelPath pathToSrcsList,
        RelPath workingDirectory,
        boolean trackClassUsage,
        boolean trackJavacPhaseEvents,
        @Nullable JarParameters abiJarParameters,
        @Nullable JarParameters libraryJarParameters,
        AbiGenerationMode abiGenerationMode,
        AbiGenerationMode abiCompatibilityMode,
        @Nullable SourceOnlyAbiRuleInfoFactory ruleInfoFactory) {
      return new Jsr199JavacInvocation(
          () -> createCompiler(context),
          context,
          invokingRule,
          libraryOutputJarDirPath,
          sourceAbiOutputJarDirPath,
          sourceOnlyAbiOutputJarDirPath,
          options,
          annotationProcessors,
          javacPlugins,
          javaSourceFilePaths,
          pathToSrcsList,
          trackClassUsage,
          trackJavacPhaseEvents,
          abiJarParameters,
          libraryJarParameters,
          abiGenerationMode,
          abiCompatibilityMode,
          ruleInfoFactory);
    }
  }
}
