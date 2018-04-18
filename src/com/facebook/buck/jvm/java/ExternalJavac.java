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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.jvm.java.abi.source.api.SourceOnlyAbiRuleInfo;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.NonHashableSourcePathContainer;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.VersionedTool;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutor.Result;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.unarchive.ArchiveFormat;
import com.facebook.buck.util.unarchive.ExistingFileMode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** javac implemented in a separate binary. */
public class ExternalJavac implements Javac {
  @AddToRuleKey private final Supplier<Tool> javac;
  private final Either<PathSourcePath, BuildTargetSourcePath> actualPath;
  private final String shortName;

  public ExternalJavac(final Either<PathSourcePath, SourcePath> pathToJavac) {
    if (pathToJavac.isRight() && pathToJavac.getRight() instanceof BuildTargetSourcePath) {
      BuildTargetSourcePath buildTargetPath = (BuildTargetSourcePath) pathToJavac.getRight();
      this.shortName = buildTargetPath.getTarget().toString();
      this.actualPath = Either.ofRight(buildTargetPath);
      this.javac =
          MoreSuppliers.memoize(
              () ->
                  new Tool() {
                    @AddToRuleKey
                    private final NonHashableSourcePathContainer container =
                        new NonHashableSourcePathContainer(buildTargetPath);

                    @Override
                    public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
                      return ImmutableList.of(
                          resolver.getAbsolutePath(container.getSourcePath()).toString());
                    }

                    @Override
                    public ImmutableMap<String, String> getEnvironment(
                        SourcePathResolver resolver) {
                      return ImmutableMap.of();
                    }
                  });
    } else {
      PathSourcePath actualPath =
          pathToJavac.transform(path -> path, path -> (PathSourcePath) path);
      this.actualPath = Either.ofLeft(actualPath);
      this.shortName = actualPath.toString();
      this.javac =
          MoreSuppliers.memoize(
              () -> {
                ProcessExecutorParams params =
                    ProcessExecutorParams.builder()
                        .setCommand(ImmutableList.of(actualPath.toString(), "-version"))
                        .build();
                Result result;
                try {
                  result = createProcessExecutor().launchAndExecute(params);
                } catch (InterruptedException | IOException e) {
                  throw new RuntimeException(e);
                }
                Optional<String> stderr = result.getStderr();
                String output = stderr.orElse("").trim();
                String version;
                if (Strings.isNullOrEmpty(output)) {
                  version = actualPath.toString();
                } else {
                  version = JavacVersion.of(output).toString();
                }
                return VersionedTool.of(actualPath, "external_javac", version);
              });
    }
  }

  @VisibleForTesting
  Either<PathSourcePath, BuildTargetSourcePath> getActualPath() {
    return actualPath;
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    return javac.get().getCommandPrefix(resolver);
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
    return javac.get().getEnvironment(resolver);
  }

  public static Javac createJavac(Either<PathSourcePath, SourcePath> pathToJavac) {
    return new ExternalJavac(pathToJavac);
  }

  @VisibleForTesting
  ProcessExecutor createProcessExecutor() {
    return new DefaultProcessExecutor(Console.createNullConsole());
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
      @Nullable SourceOnlyAbiRuleInfo ruleInfo) {
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
        ImmutableList.Builder<String> command = ImmutableList.builder();
        command.add(
            actualPath.transform(
                Object::toString, path -> sourcePathResolver.getAbsolutePath(path).toString()));
        ImmutableList<Path> expandedSources;
        try {
          expandedSources =
              getExpandedSourcePaths(
                  context.getProjectFilesystem(),
                  context.getProjectFilesystemFactory(),
                  javaSourceFilePaths,
                  workingDirectory);
        } catch (IOException e) {
          throw new HumanReadableException(
              "Unable to expand sources for %s into %s", invokingRule, workingDirectory);
        }

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

  private ImmutableList<Path> getExpandedSourcePaths(
      ProjectFilesystem projectFilesystem,
      ProjectFilesystemFactory projectFilesystemFactory,
      ImmutableSet<Path> javaSourceFilePaths,
      Path workingDirectory)
      throws InterruptedException, IOException {

    // Add sources file or sources list to command
    ImmutableList.Builder<Path> sources = ImmutableList.builder();
    for (Path path : javaSourceFilePaths) {
      String pathString = path.toString();
      if (pathString.endsWith(".java")) {
        sources.add(path);
      } else if (pathString.endsWith(SRC_ZIP) || pathString.endsWith(SRC_JAR)) {
        // For a Zip of .java files, create a JavaFileObject for each .java entry.
        ImmutableList<Path> zipPaths =
            ArchiveFormat.ZIP
                .getUnarchiver()
                .extractArchive(
                    projectFilesystemFactory,
                    projectFilesystem.resolve(path),
                    projectFilesystem.resolve(workingDirectory),
                    ExistingFileMode.OVERWRITE);
        sources.addAll(
            zipPaths.stream().filter(input -> input.toString().endsWith(".java")).iterator());
      }
    }
    return sources.build();
  }
}
