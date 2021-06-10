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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.javacd.model.AbiGenerationMode;
import com.facebook.buck.javacd.model.ResolvedJavacOptions.JavacPluginJsr199Fields;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.java.abi.source.api.SourceOnlyAbiRuleInfoFactory;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.util.Collection;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** javac implemented in a separate binary. */
public class ExternalJavac implements Javac {

  private static final Logger LOG = Logger.get(ExternalJavac.class);

  private static final int SUCCESS_EXIT_CODE = 0;
  private static final int ERROR_EXIT_CODE = 1;

  @AddToRuleKey private final Supplier<Tool> javac;
  private final String shortName;

  public ExternalJavac(Supplier<Tool> javac, String shortName) {
    this.javac = MoreSuppliers.memoize(javac);
    this.shortName = shortName;
  }

  /** External resolved external javac tool. */
  public static class ResolvedExternalJavac implements ResolvedJavac {

    private final String shortName;
    private final ImmutableList<String> commandPrefix;
    // maybe make it configurable
    private final ImmutableList<String> commonJavacOptions =
        ImmutableList.of("-encoding", UTF_8.name().toLowerCase());

    public ResolvedExternalJavac(String shortName, ImmutableList<String> commandPrefix) {
      this.shortName = shortName;
      this.commandPrefix = commandPrefix;
    }

    @Override
    public String getDescription(
        ImmutableList<String> options,
        ImmutableSortedSet<RelPath> javaSourceFilePaths,
        RelPath pathToSrcsList) {
      StringBuilder builder = new StringBuilder(getShortName());
      appendToBuilder(builder, commonJavacOptions);
      appendToBuilder(builder, options);
      builder.append(" @").append(pathToSrcsList);
      return builder.toString();
    }

    private void appendToBuilder(StringBuilder builder, Collection<String> collection) {
      String delimiter = " ";
      if (!collection.isEmpty()) {
        builder.append(delimiter);
        Joiner.on(delimiter).appendTo(builder, collection);
      }
    }

    @Override
    public String getShortName() {
      return shortName;
    }

    public ImmutableList<String> getCommandPrefix() {
      return commandPrefix;
    }

    @Override
    public Invocation newBuildInvocation(
        JavacExecutionContext context,
        BuildTargetValue invokingRule,
        CompilerOutputPathsValue compilerOutputPathsValue,
        ImmutableList<String> options,
        ImmutableList<JavacPluginJsr199Fields> annotationProcessors,
        ImmutableList<JavacPluginJsr199Fields> javacPlugins,
        ImmutableSortedSet<RelPath> javaSourceFilePaths,
        RelPath pathToSrcsList,
        RelPath workingDirectory,
        boolean trackClassUsage,
        boolean trackJavacPhaseEvents,
        @Nullable JarParameters abiJarParameters,
        @Nullable JarParameters libraryJarParameters,
        AbiGenerationMode abiGenerationMode,
        AbiGenerationMode abiCompatibilityMode,
        @Nullable SourceOnlyAbiRuleInfoFactory ruleInfoFactory) {
      Preconditions.checkArgument(abiJarParameters == null);
      Preconditions.checkArgument(libraryJarParameters == null);
      Preconditions.checkArgument(
          abiGenerationMode == AbiGenerationMode.CLASS,
          "Cannot compile ABI jars with external javac");

      return new Invocation() {
        @Override
        public int buildSourceOnlyAbiJar() {
          throw new UnsupportedOperationException(
              "Cannot build source-only ABI jar with external javac.");
        }

        @Override
        public int buildSourceAbiJar() {
          throw new UnsupportedOperationException(
              "Cannot build source ABI jar with external javac.");
        }

        @Override
        public int buildClasses() throws InterruptedException {
          // For consistency with javax.tools.JavaCompiler, if no sources are specified, then do
          // nothing. Although it seems reasonable to treat this case as an error, we have a
          // situation in KotlincToJarStepFactory where we need to categorically add a JavacStep in
          // the event
          // that an annotation processor for the kotlin_library() dynamically generates some .java
          // files that need to be compiled. Often, the annotation processors will do no such thing
          // and the JavacStep that was added will have no work to do.
          ImmutableList<RelPath> expandedSources = getExpandedSources();
          if (expandedSources.isEmpty()) {
            return SUCCESS_EXIT_CODE;
          }

          ImmutableList.Builder<String> command = ImmutableList.builder();
          command.addAll(commandPrefix);

          Stream<String> commonOptionsStream = commonJavacOptions.stream();
          Stream<String> optionsStream = options.stream();
          Stream<String> sourcesStream = expandedSources.stream().map(Object::toString);
          Stream<String> stream =
              Stream.concat(commonOptionsStream, Stream.concat(optionsStream, sourcesStream));
          try {
            ProjectFilesystemUtils.writeLinesToPath(
                context.getRuleCellRoot(),
                () -> stream.map(ResolvedJavac.ARGFILES_ESCAPER).iterator(),
                pathToSrcsList.getPath());
            command.add("@" + pathToSrcsList);
          } catch (IOException e) {
            context
                .getEventSink()
                .reportThrowable(
                    e,
                    "Cannot write list of args/sources to compile to %s file! Terminating compilation.",
                    pathToSrcsList);
            return ERROR_EXIT_CODE;
          }

          return runCommand(command.build());
        }

        private ImmutableList<RelPath> getExpandedSources() {
          try {
            return JavaPaths.extractArchivesAndGetPaths(
                context.getRuleCellRoot(), javaSourceFilePaths, workingDirectory.getPath());
          } catch (IOException e) {
            throw new HumanReadableException(
                "Unable to expand sources for %s into %s",
                invokingRule.getFullyQualifiedName(), workingDirectory);
          }
        }

        private int runCommand(ImmutableList<String> command) throws InterruptedException {
          LOG.info("Running javac command: %s", command);
          try {
            ProcessExecutorParams params =
                ProcessExecutorParams.builder()
                    .setCommand(command)
                    .setEnvironment(context.getEnvironment())
                    .setDirectory(context.getRuleCellRoot().getPath())
                    .build();
            ProcessExecutor processExecutor = context.getProcessExecutor();
            ProcessExecutor.Result result = processExecutor.launchAndExecute(params);
            return result.getExitCode();
          } catch (IOException e) {
            e.printStackTrace(context.getStdErr());
          }
          return ERROR_EXIT_CODE;
        }

        @Override
        public void close() {
          // Nothing to do
        }
      };
    }
  }

  @Override
  public ResolvedExternalJavac resolve(SourcePathResolverAdapter resolver, AbsPath ruleCellRoot) {
    ImmutableList<String> commandPrefix = javac.get().getCommandPrefix(resolver);
    return new ResolvedExternalJavac(shortName, commandPrefix);
  }
}
