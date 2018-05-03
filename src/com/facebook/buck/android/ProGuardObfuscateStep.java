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

package com.facebook.buck.android;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.util.zip.CustomZipOutputStream;
import com.facebook.buck.util.zip.ZipOutputStreams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;

public final class ProGuardObfuscateStep extends ShellStep {

  enum SdkProguardType {
    DEFAULT,
    OPTIMIZED,
    NONE,
  }

  private final AndroidPlatformTarget androidPlatformTarget;
  private final ImmutableList<String> javaRuntimeLauncher;
  private final ProjectFilesystem filesystem;
  private final Map<Path, Path> inputAndOutputEntries;
  private final Path pathToProGuardCommandLineArgsFile;
  private final boolean skipProguard;
  private final Optional<Path> proguardJarOverride;
  private final String proguardMaxHeapSize;
  private final Optional<List<String>> proguardJvmArgs;
  private final Optional<String> proguardAgentPath;

  /**
   * Create steps that write out ProGuard's command line arguments to a text file and then run
   * ProGuard using those arguments. We write the arguments to a file to avoid blowing out exec()'s
   * ARG_MAX limit.
   *
   * @param steps Where to append the generated steps.
   */
  public static void create(
      BuildTarget target,
      AndroidPlatformTarget androidPlatformTarget,
      ImmutableList<String> javaRuntimeLauncher,
      ProjectFilesystem filesystem,
      Optional<Path> proguardJarOverride,
      String proguardMaxHeapSize,
      Optional<String> proguardAgentPath,
      Set<Path> customProguardConfigs,
      SdkProguardType sdkProguardConfig,
      Optional<Integer> optimizationPasses,
      Optional<List<String>> proguardJvmArgs,
      Map<Path, Path> inputAndOutputEntries,
      Set<Path> additionalLibraryJarsForProguard,
      Path proguardDirectory,
      BuildableContext buildableContext,
      BuildContext buildContext,
      boolean skipProguard,
      ImmutableList.Builder<Step> steps) {

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), filesystem, proguardDirectory)));

    Path pathToProGuardCommandLineArgsFile = proguardDirectory.resolve("command-line.txt");

    CommandLineHelperStep commandLineHelperStep =
        new CommandLineHelperStep(
            filesystem,
            androidPlatformTarget,
            customProguardConfigs,
            sdkProguardConfig,
            optimizationPasses,
            inputAndOutputEntries,
            additionalLibraryJarsForProguard,
            proguardDirectory,
            pathToProGuardCommandLineArgsFile);

    if (skipProguard) {
      steps.add(
          commandLineHelperStep, new TouchStep(filesystem, commandLineHelperStep.getMappingTxt()));
    } else {
      ProGuardObfuscateStep proGuardStep =
          new ProGuardObfuscateStep(
              target,
              androidPlatformTarget,
              javaRuntimeLauncher,
              filesystem,
              inputAndOutputEntries,
              pathToProGuardCommandLineArgsFile,
              skipProguard,
              proguardJarOverride,
              proguardMaxHeapSize,
              proguardJvmArgs,
              proguardAgentPath);

      buildableContext.recordArtifact(commandLineHelperStep.getConfigurationTxt());
      buildableContext.recordArtifact(commandLineHelperStep.getMappingTxt());
      buildableContext.recordArtifact(commandLineHelperStep.getSeedsTxt());

      steps.add(
          commandLineHelperStep,
          proGuardStep,
          // Some proguard configs can propagate the "-dontobfuscate" flag which disables
          // obfuscation and prevents the mapping.txt file from being generated.  So touch it
          // here to guarantee it's around when we go to cache this rule.
          new TouchStep(filesystem, commandLineHelperStep.getMappingTxt()));
    }
  }

  /**
   * @param inputAndOutputEntries Map of input/output pairs to proguard. The key represents an input
   *     jar (-injars); the value an output jar (-outjars).
   * @param pathToProGuardCommandLineArgsFile Path to file containing arguments to ProGuard.
   */
  private ProGuardObfuscateStep(
      BuildTarget buildTarget,
      AndroidPlatformTarget androidPlatformTarget,
      ImmutableList<String> javaRuntimeLauncher,
      ProjectFilesystem filesystem,
      Map<Path, Path> inputAndOutputEntries,
      Path pathToProGuardCommandLineArgsFile,
      boolean skipProguard,
      Optional<Path> proguardJarOverride,
      String proguardMaxHeapSize,
      Optional<List<String>> proguardJvmArgs,
      Optional<String> proguardAgentPath) {
    super(Optional.of(buildTarget), filesystem.getRootPath());
    this.androidPlatformTarget = androidPlatformTarget;
    this.javaRuntimeLauncher = javaRuntimeLauncher;
    this.filesystem = filesystem;
    this.inputAndOutputEntries = ImmutableMap.copyOf(inputAndOutputEntries);
    this.pathToProGuardCommandLineArgsFile = pathToProGuardCommandLineArgsFile;
    this.skipProguard = skipProguard;
    this.proguardJarOverride = proguardJarOverride;
    this.proguardMaxHeapSize = proguardMaxHeapSize;
    this.proguardJvmArgs = proguardJvmArgs;
    this.proguardAgentPath = proguardAgentPath;
  }

  @Override
  public String getShortName() {
    return "proguard_obfuscation";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    // Run ProGuard as a standalone executable JAR file.
    Path proguardJar;
    if (proguardJarOverride.isPresent()) {
      proguardJar = filesystem.getPathForRelativePath(proguardJarOverride.get());
    } else {
      proguardJar = androidPlatformTarget.getProguardJar();
    }

    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.addAll(javaRuntimeLauncher);
    if (proguardAgentPath.isPresent()) {
      args.add("-agentpath:" + proguardAgentPath.get());
    }
    if (proguardJvmArgs.isPresent()) {
      args.addAll(proguardJvmArgs.get());
    }
    args.add("-Xmx" + proguardMaxHeapSize)
        .add("-jar")
        .add(proguardJar.toString())
        .add("@" + pathToProGuardCommandLineArgsFile);
    return args.build();
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    StepExecutionResult executionResult = super.execute(context);

    // proguard has a peculiar behaviour when multiple -injars/outjars pairs are specified in which
    // any -injars that would have been fully stripped away will not produce their matching -outjars
    // as requested (so the file won't exist).  Our build steps are not sophisticated enough to
    // account for this and remove those entries from the classes to dex so we hack things here to
    // ensure that the files exist but are empty.
    if (executionResult.isSuccess() && !this.skipProguard) {
      return StepExecutionResult.of(ensureAllOutputsExist(context));
    }

    return executionResult;
  }

  private int ensureAllOutputsExist(ExecutionContext context) {
    for (Path outputJar : inputAndOutputEntries.values()) {
      if (!Files.exists(outputJar)) {
        try {
          createEmptyZip(outputJar);
        } catch (IOException e) {
          context.logError(e, "Error creating empty zip file at: %s.", outputJar);
          return 1;
        }
      }
    }
    return 0;
  }

  @VisibleForTesting
  static void createEmptyZip(Path file) throws IOException {
    Files.createDirectories(file.getParent());
    CustomZipOutputStream out = ZipOutputStreams.newOutputStream(file);
    // Sun's java 6 runtime doesn't allow us to create a truly empty zip, but this should be enough
    // to pass through dx/split-zip without any issue.
    // ...and Sun's java 7 runtime doesn't let us use an empty string for the zip entry name.
    out.putNextEntry(new ZipEntry("proguard_no_result"));
    out.close();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    } else if (!(obj instanceof ProGuardObfuscateStep)) {
      return false;
    }

    ProGuardObfuscateStep that = (ProGuardObfuscateStep) obj;
    return Objects.equal(this.inputAndOutputEntries, that.inputAndOutputEntries)
        && Objects.equal(
            this.pathToProGuardCommandLineArgsFile, that.pathToProGuardCommandLineArgsFile);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(inputAndOutputEntries, pathToProGuardCommandLineArgsFile);
  }

  /**
   * Helper class to run as a step before ProGuardObfuscateStep to write out the command-line
   * parameters to a file. The ProGuardObfuscateStep references this file when it runs using
   * ProGuard's '@' syntax. This allows for longer command-lines than would otherwise be supported.
   */
  @VisibleForTesting
  static class CommandLineHelperStep extends AbstractExecutionStep {

    private final ProjectFilesystem filesystem;
    private final AndroidPlatformTarget androidPlatformTarget;
    private final Set<Path> customProguardConfigs;
    private final Map<Path, Path> inputAndOutputEntries;
    private final ImmutableSet<Path> additionalLibraryJarsForProguard;
    private final SdkProguardType sdkProguardConfig;
    private final Optional<Integer> optimizationPasses;
    private final Path proguardDirectory;
    private final Path pathToProGuardCommandLineArgsFile;

    /**
     * @param customProguardConfigs Main rule and its dependencies proguard configurations.
     * @param sdkProguardConfig Which proguard config from the Android SDK to use.
     * @param inputAndOutputEntries Map of input/output pairs to proguard. The key represents an
     *     input jar (-injars); the value an output jar (-outjars).
     * @param additionalLibraryJarsForProguard Libraries that are not operated upon by proguard but
     *     needed to resolve symbols.
     * @param proguardDirectory Output directory for various proguard-generated meta artifacts.
     * @param pathToProGuardCommandLineArgsFile Path to file containing arguments to ProGuard.
     */
    private CommandLineHelperStep(
        ProjectFilesystem filesystem,
        AndroidPlatformTarget androidPlatformTarget,
        Set<Path> customProguardConfigs,
        SdkProguardType sdkProguardConfig,
        Optional<Integer> optimizationPasses,
        Map<Path, Path> inputAndOutputEntries,
        Set<Path> additionalLibraryJarsForProguard,
        Path proguardDirectory,
        Path pathToProGuardCommandLineArgsFile) {
      super("write_proguard_command_line_parameters");

      this.filesystem = filesystem;
      this.androidPlatformTarget = androidPlatformTarget;
      this.customProguardConfigs = ImmutableSet.copyOf(customProguardConfigs);
      this.sdkProguardConfig = sdkProguardConfig;
      this.optimizationPasses = optimizationPasses;
      this.inputAndOutputEntries = ImmutableMap.copyOf(inputAndOutputEntries);
      this.additionalLibraryJarsForProguard = ImmutableSet.copyOf(additionalLibraryJarsForProguard);
      this.proguardDirectory = proguardDirectory;
      this.pathToProGuardCommandLineArgsFile = pathToProGuardCommandLineArgsFile;
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context)
        throws IOException, InterruptedException {
      String proGuardArguments = Joiner.on('\n').join(getParameters(filesystem.getRootPath()));
      filesystem.writeContentsToPath(proGuardArguments, pathToProGuardCommandLineArgsFile);

      return StepExecutionResults.SUCCESS;
    }

    /** @return the list of arguments to pass to ProGuard. */
    @VisibleForTesting
    ImmutableList<String> getParameters(Path workingDirectory) {
      ImmutableList.Builder<String> args = ImmutableList.builder();

      // Relative paths should be interpreted relative to project directory root, not the
      // written parameters file.
      args.add("-basedirectory").add(workingDirectory.toAbsolutePath().toString());

      // -include
      switch (sdkProguardConfig) {
        case OPTIMIZED:
          args.add("-include").add(androidPlatformTarget.getOptimizedProguardConfig().toString());
          if (optimizationPasses.isPresent()) {
            args.add("-optimizationpasses").add(optimizationPasses.get().toString());
          }
          break;
        case DEFAULT:
          args.add("-include").add(androidPlatformTarget.getProguardConfig().toString());
          break;
        case NONE:
          break;
        default:
          throw new RuntimeException("Illegal value for sdkProguardConfig: " + sdkProguardConfig);
      }
      for (Path proguardConfig : customProguardConfigs) {
        args.add("-include").add(proguardConfig.toString());
      }

      // -injars and -outjars paired together for each input.
      for (Map.Entry<Path, Path> inputOutputEntry : inputAndOutputEntries.entrySet()) {
        args.add("-injars").add(inputOutputEntry.getKey().toString());
        args.add("-outjars").add(inputOutputEntry.getValue().toString());
      }

      // -libraryjars
      Iterable<Path> bootclasspathPaths = androidPlatformTarget.getBootclasspathEntries();
      Iterable<Path> libraryJars =
          Iterables.concat(bootclasspathPaths, additionalLibraryJarsForProguard);

      Character separator = File.pathSeparatorChar;
      args.add("-libraryjars").add(Joiner.on(separator).join(libraryJars));

      // -dump
      args.add("-printmapping").add(getMappingTxt().toString());
      args.add("-printconfiguration").add(getConfigurationTxt().toString());
      args.add("-printseeds").add(getSeedsTxt().toString());

      return args.build();
    }

    public Path getConfigurationTxt() {
      return proguardDirectory.resolve("configuration.txt");
    }

    public Path getMappingTxt() {
      return proguardDirectory.resolve("mapping.txt");
    }

    public Path getSeedsTxt() {
      return proguardDirectory.resolve("seeds.txt");
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof CommandLineHelperStep)) {
        return false;
      }
      CommandLineHelperStep that = (CommandLineHelperStep) obj;

      return Objects.equal(sdkProguardConfig, that.sdkProguardConfig)
          && Objects.equal(additionalLibraryJarsForProguard, that.additionalLibraryJarsForProguard)
          && Objects.equal(customProguardConfigs, that.customProguardConfigs)
          && Objects.equal(inputAndOutputEntries, that.inputAndOutputEntries)
          && Objects.equal(proguardDirectory, that.proguardDirectory)
          && Objects.equal(
              pathToProGuardCommandLineArgsFile, that.pathToProGuardCommandLineArgsFile);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(
          sdkProguardConfig,
          additionalLibraryJarsForProguard,
          customProguardConfigs,
          inputAndOutputEntries,
          proguardDirectory,
          pathToProGuardCommandLineArgsFile);
    }
  }
}
