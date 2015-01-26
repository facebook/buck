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

package com.facebook.buck.apple;

import com.facebook.buck.apple.clang.HeaderMap;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey.Builder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.VersionStringComparator;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.Files;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Build rule that generates a <a href="http://clang.llvm.org/docs/JSONCompilationDatabase.html">
 * clang compilation database</a> for an Apple target.
 *
 * TODO(mbolin): t5879160 Generate the compilation database based on a set of CxxCompile actions
 * rather than a TargetSources object so the set of build flags is legit.
 */
public class CompilationDatabase extends AbstractBuildRule {

  public static final Flavor COMPILATION_DATABASE = ImmutableFlavor.of("compilation-database");


  private final AppleConfig appleConfig;
  private final TargetSources targetSources;
  private final Path outputJsonFile;
  private final ImmutableSortedSet<String> frameworks;
  private final ImmutableSet<Path> includePaths;
  private final Optional<SourcePath> pchFile;

  /**
   * @param buildRuleParams As needed by superclass constructor.
   * @param resolver As needed by superclass constructor.
   * @param targetSources The {@link TargetSources#getHeaderPaths()} and
   *     {@link TargetSources#getSrcPaths()} will be the entries in the generated compilation
   *     database.
   * @param frameworks Paths to frameworks to link against. Each may start with {@code "$SDKROOT"},
   *     in which case the appropriate path will be substituted.
   * @param includePaths Paths that should be passed as clang args with {@code -I}.
   * @param pchFile If specified, including as a {@code -include} clang arg.
   */
  CompilationDatabase(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      AppleConfig appleConfig,
      TargetSources targetSources,
      ImmutableSortedSet<String> frameworks,
      ImmutableSet<Path> includePaths,
      Optional<SourcePath> pchFile) {
    super(buildRuleParams, resolver);
    this.appleConfig = appleConfig;
    this.targetSources = targetSources;
    this.outputJsonFile = BuildTargets.getGenPath(
        buildRuleParams.getBuildTarget(),
        "__%s_compilation_database.json");
    this.frameworks = frameworks;
    this.includePaths = includePaths;
    this.pchFile = pchFile;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // If set, this header map will be passed via -iquote to clang. In practice, this has been seen
    // to be necessary when the .pch uses quoted imports for headers that exist in a subdirectory of
    // the project, such as Categories.
    final AtomicReference<Path> internalHeaderMap = new AtomicReference<>();
    final Path headerMapPath = BuildTargets.getBinPath(getBuildTarget(), "__my_%s__.hmap");
    steps.add(new MkdirStep(headerMapPath.getParent()));
    steps.add(new AbstractExecutionStep("generate_internal_header_map") {
      @Override
      public int execute(ExecutionContext context) {
        if (targetSources.getHeaderPaths().isEmpty()) {
          return 0;
        }

        HeaderMap.Builder builder = HeaderMap.builder();
        ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
        for (SourcePath headerPath : targetSources.getHeaderPaths()) {
          Path relativePath = getResolver().getPath(headerPath);
          Path absolutePath = projectFilesystem.resolve(relativePath);
          builder.add(relativePath.getFileName().toString(), absolutePath);
        }
        HeaderMap headerMap = builder.build();
        try {
          projectFilesystem.writeBytesToPath(
              headerMap.getBytes(),
              headerMapPath);
        } catch (IOException e) {
          context.logError(e, "Failed to write header map: %s.", headerMapPath);
          return 1;
        }

        internalHeaderMap.set(headerMapPath);
        return 0;
      }
    });

    steps.add(new MkdirStep(getPathToOutputFile().getParent()));
    steps.add(new GenerateCompilationCommandsJson(internalHeaderMap));

    return steps.build();
  }

  @Override
  public Path getPathToOutputFile() {
    return outputJsonFile;
  }

  @Override
  protected ImmutableCollection<Path> getInputsToCompareToOutput() {
    return getResolver().filterInputsToCompareToOutput(
        Iterables.concat(targetSources.getHeaderPaths(), targetSources.getSrcPaths()));
  }

  @Override
  protected Builder appendDetailsToRuleKey(Builder builder) {
    // TODO(mbolin): If this contains absolute paths, need to add information to the builder that
    // makes it specific to the developer's machine and root directory.
    // Also need to include all of the information from the other fields.
    return builder;
  }

  class GenerateCompilationCommandsJson extends AbstractExecutionStep {

    private final AtomicReference<Path> internalHeaderMap;

    public GenerateCompilationCommandsJson(AtomicReference<Path> internalHeaderMap) {
      super("generate compile_commands.json");
      this.internalHeaderMap = internalHeaderMap;
    }

    @Override
    public int execute(ExecutionContext context) {
      Iterable<JsonSerializableDatabaseEntry> entries = createEntries(context);
      return writeOutput(entries, context);
    }

    @VisibleForTesting
    Iterable<JsonSerializableDatabaseEntry> createEntries(ExecutionContext context) {
      BuildTarget target = getBuildTarget();
      List<JsonSerializableDatabaseEntry> entries = Lists.newArrayList();
      Iterable<SourcePath> allSources = Iterables.concat(
          targetSources.getSrcPaths(),
          targetSources.getHeaderPaths());
      ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
      for (SourcePath srcPath : allSources) {
        String fileToCompile = projectFilesystem.resolve(getResolver().getPath(srcPath))
            .toString();
        String language;
        String languageStandard;
        if (fileToCompile.endsWith(".mm")) {
          language = "objective-c++";
          languageStandard = "-std=c++11";
        } else {
          language = "objective-c";
          languageStandard = "-std=gnu99";
        }

        List<String> commandArgs = Lists.newArrayList(
            "clang",
            "-x",
            language,

            // TODO(mbolin): Simulator arguments should be configurable (and should likely be
            // derived from the PlatformFlavor).
            "-arch",
            "i386",
            "-mios-simulator-version-min=7.0",

            "-fmessage-length=0",
            "-fdiagnostics-show-note-include-stack",
            "-fmacro-backtrace-limit=0",
            languageStandard,
            "-fpascal-strings",
            "-fexceptions",
            "-fasm-blocks",
            "-fstrict-aliasing",
            "-fobjc-abi-version=2",
            "-fobjc-legacy-dispatch",

            "-O0", // No optimizations.

            // TODO(mbolin): Include all of the -W and -D flags.

            // TODO(mbolin): Support -MMD, -MT, -MF. Requires -o to trigger it.

            "-g", // Generate source level debug information.
            "-MMD" // Write a depfile containing user headers.
            );

        // TODO(mbolin): Determine whether -fno-objc-arc should be used instead.
        commandArgs.add("-fobjc-arc");

        // Result of `xcode-select --print-path`.
        ImmutableMap<AppleSdk, AppleSdkPaths> allAppleSdkPaths = appleConfig.getAppleSdkPaths(
            context.getProcessExecutor());
        AppleSdkPaths appleSdkPaths = selectNewestSimulatorSdk(allAppleSdkPaths);

        // TODO(mbolin): Make the sysroot configurable.
        commandArgs.add("-isysroot");
        Path sysroot = appleSdkPaths.getSdkPath();
        commandArgs.add(sysroot.toString());

        String sdkRoot = appleSdkPaths.getSdkPath().toString();
        for (String framework : frameworks) {
          // TODO(mbolin): Other placeholders are possible, but do not appear to be used yet.
          // Specifically, PBXReference.SourceTree#fromBuildSetting() seems to have more
          // flexible parsing. We should figure out how to refactor that could so it can be used
          // here.
          framework = framework.replace("$SDKROOT", sdkRoot);
          commandArgs.add("-F" + framework);
        }

        // Add -I and -iquote flags, as appropriate.
        for (Path includePath : includePaths) {
          commandArgs.add("-I" + projectFilesystem.resolve(includePath));
        }

        Path iquoteArg = internalHeaderMap.get();
        if (iquoteArg != null) {
          commandArgs.add("-iquote");
          commandArgs.add(projectFilesystem.resolve(iquoteArg).toString());
        }

        if (pchFile.isPresent()) {
          commandArgs.add("-include");
          Path relativePathToPchFile = getResolver().getPath(pchFile.get());
          commandArgs.add(projectFilesystem.resolve(relativePathToPchFile).toString());
        }

        commandArgs.add("-c");
        commandArgs.add(fileToCompile);

        // Currently, perFileFlags is a single string rather than a list, so we concatenate it
        // to the end of the command string without escaping or splitting.
        String perFileFlags = Strings.nullToEmpty(targetSources.getPerFileFlags().get(srcPath));
        if (!perFileFlags.isEmpty() && FileExtensions.CLANG_SOURCES.contains(
            Files.getFileExtension(fileToCompile))) {
          commandArgs.add(perFileFlags);
        }

        String command = Joiner.on(' ').join(commandArgs);
        entries.add(new JsonSerializableDatabaseEntry(
            /* directory */ projectFilesystem.resolve(target.getBasePath()).toString(),
            fileToCompile,
            command));
      }

      return entries;
    }

    private int writeOutput(
        Iterable<JsonSerializableDatabaseEntry> entries,
        ExecutionContext context) {
      ObjectMapper mapper = new ObjectMapper();
      try {
        OutputStream outputStream = context.getProjectFilesystem().newFileOutputStream(
            getPathToOutputFile());
        mapper.writeValue(outputStream, entries);
      } catch (IOException e) {
        logError(e, context);
        return 1;
      }

      return 0;
    }

    private void logError(Throwable throwable, ExecutionContext context) {
      context.logError(
          throwable,
          "Failed writing to %s in %s.",
          getPathToOutputFile(),
          getBuildTarget());
    }
  }

  // TODO(mbolin): This method should go away when the sdkName becomes a flavor.
  static AppleSdkPaths selectNewestSimulatorSdk(
      ImmutableMap<AppleSdk, AppleSdkPaths> allAppleSdkPaths) {
    Ordering<AppleSdk> appleSdkVersionComparator =
        Ordering
            .from(new VersionStringComparator())
            .onResultOf(new Function<AppleSdk, String>() {
                @Override
                public String apply(AppleSdk appleSdk) {
                    return appleSdk.getVersion();
                }
            });

    ImmutableSortedSet<AppleSdk> sortedIphoneSimulatorSdks = FluentIterable
        .from(allAppleSdkPaths.keySet())
        .filter(new Predicate<AppleSdk>() {
          @Override
          public boolean apply(AppleSdk sdk) {
            return sdk.getApplePlatform() == ApplePlatform.IPHONESIMULATOR;
          }
        })
        .toSortedSet(appleSdkVersionComparator);
    if (sortedIphoneSimulatorSdks.isEmpty()) {
      throw new RuntimeException("No iphonesimulator found in: " + allAppleSdkPaths.keySet());
    }

    return Preconditions.checkNotNull(allAppleSdkPaths.get(sortedIphoneSimulatorSdks.last()));
  }

  @VisibleForTesting
  @SuppressFieldNotInitialized
  static class JsonSerializableDatabaseEntry {

    public String directory;
    public String file;
    public String command;

    /** Empty constructor will be used by Jackson. */
    public JsonSerializableDatabaseEntry() {}

    public JsonSerializableDatabaseEntry(String directory, String file, String command) {
      this.directory = directory;
      this.file = file;
      this.command = command;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof JsonSerializableDatabaseEntry)) {
        return false;
      }

      JsonSerializableDatabaseEntry that = (JsonSerializableDatabaseEntry) obj;
      return Objects.equal(this.directory, that.directory) &&
          Objects.equal(this.file, that.file) &&
          Objects.equal(this.command, that.command);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(directory, file, command);
    }

    // Useful if CompilationDatabaseTest fails when comparing JsonSerializableDatabaseEntry objects.
    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("directory", directory)
          .add("file", file)
          .add("command", command)
          .toString();
    }
  }
}
