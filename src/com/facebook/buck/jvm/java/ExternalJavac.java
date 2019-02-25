/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.jvm.java.abi.source.api.SourceOnlyAbiRuleInfoFactory;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** javac implemented in a separate binary. */
public class ExternalJavac implements Javac {
  @AddToRuleKey private final Supplier<Tool> javac;
  private final String shortName;

  public ExternalJavac(Supplier<Tool> javac, String shortName) {
    this.javac = MoreSuppliers.memoize(javac);
    this.shortName = shortName;
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    return javac.get().getCommandPrefix(resolver);
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
    return javac.get().getEnvironment(resolver);
  }

  @Override
  public String getDescription(
      ImmutableList<String> options,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList) {
    StringBuilder builder = new StringBuilder(getShortName());
    builder.append(" ");
    Joiner.on(" ").appendTo(builder, options);
    builder.append(" ");
    builder.append("@").append(pathToSrcsList);

    return builder.toString();
  }

  @Override
  public String getShortName() {
    return shortName;
  }

  @Override
  public Invocation newBuildInvocation(
      JavacExecutionContext context,
      SourcePathResolver sourcePathResolver,
      BuildTarget invokingRule,
      ImmutableList<String> options,
      ImmutableList<JavacPluginJsr199Fields> pluginFields,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList,
      Path workingDirectory,
      boolean trackClassUsage,
      boolean trackJavacPhaseEvents,
      @Nullable JarParameters abiJarParaameters,
      @Nullable JarParameters libraryJarParameters,
      AbiGenerationMode abiGenerationMode,
      AbiGenerationMode abiCompatibilityMode,
      @Nullable SourceOnlyAbiRuleInfoFactory ruleInfoFactory) {
    Preconditions.checkArgument(abiJarParaameters == null);
    Preconditions.checkArgument(libraryJarParameters == null);

    return new Invocation() {
      @Override
      public int buildSourceOnlyAbiJar() {
        throw new UnsupportedOperationException(
            "Cannot build source-only ABI jar with external javac.");
      }

      @Override
      public int buildSourceAbiJar() {
        throw new UnsupportedOperationException("Cannot build source ABI jar with external javac.");
      }

      @Override
      public int buildClasses() throws InterruptedException {
        Preconditions.checkArgument(
            abiGenerationMode == AbiGenerationMode.CLASS,
            "Cannot compile ABI jars with external javac");
        ImmutableList<Path> expandedSources;
        try {
          expandedSources =
              JavaPaths.extractArchivesAndGetPaths(
                  context.getProjectFilesystem(),
                  context.getProjectFilesystemFactory(),
                  javaSourceFilePaths,
                  workingDirectory);
        } catch (IOException e) {
          throw new HumanReadableException(
              "Unable to expand sources for %s into %s", invokingRule, workingDirectory);
        }

        // For consistency with javax.tools.JavaCompiler, if no sources are specified, then do
        // nothing. Although it seems reasonable to treat this case as an error, we have a situation
        // in KotlincToJarStepFactory where we need to categorically add a JavacStep in the event
        // that an annotation processor for the kotlin_library() dynamically generates some .java
        // files that need to be compiled. Often, the annotation processors will do no such thing
        // and the JavacStep that was added will have no work to do.
        if (expandedSources.isEmpty()) {
          return 0;
        }

        ImmutableList.Builder<String> command = ImmutableList.builder();
        command.addAll(javac.get().getCommandPrefix(sourcePathResolver));

        try {
          FluentIterable<String> escapedPaths =
              FluentIterable.from(expandedSources)
                  .transform(Object::toString)
                  .transform(ARGFILES_ESCAPER::apply);
          FluentIterable<String> escapedArgs =
              FluentIterable.from(options).transform(ARGFILES_ESCAPER::apply);

          context
              .getProjectFilesystem()
              .writeLinesToPath(Iterables.concat(escapedArgs, escapedPaths), pathToSrcsList);
          command.add("@" + pathToSrcsList);
        } catch (IOException e) {
          context
              .getEventSink()
              .reportThrowable(
                  e,
                  "Cannot write list of args/sources to compile to %s file! Terminating compilation.",
                  pathToSrcsList);
          return 1;
        }

        // Run the command
        int exitCode = -1;
        try {
          ProcessExecutorParams params =
              ProcessExecutorParams.builder()
                  .setCommand(command.build())
                  .setEnvironment(context.getEnvironment())
                  .setDirectory(context.getProjectFilesystem().getRootPath().toAbsolutePath())
                  .build();
          ProcessExecutor.Result result = context.getProcessExecutor().launchAndExecute(params);
          exitCode = result.getExitCode();
        } catch (IOException e) {
          e.printStackTrace(context.getStdErr());
          return exitCode;
        }

        return exitCode;
      }

      @Override
      public void close() {
        // Nothing to do
      }
    };
  }
}
