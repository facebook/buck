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

import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.zip.CustomZipOutputStream;
import com.facebook.buck.zip.ZipOutputStreams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;

public final class ProGuardObfuscateStep extends ShellStep {

  static enum SdkProguardType {
    DEFAULT,
    OPTIMIZED,
    NONE,
  }

  private final Map<Path, Path> inputAndOutputEntries;
  private final Path pathToProGuardCommandLineArgsFile;
  private final Optional<Path> proguardJarOverride;
  private final String proguardMaxHeapSize;

  /**
   * Create steps that write out ProGuard's command line arguments to a text file and then run
   * ProGuard using those arguments. We write the arguments to a file to avoid blowing out
   * exec()'s ARG_MAX limit.
   *
   * @param steps Where to append the generated steps.
   */
  public static void create(
      Optional<Path> proguardJarOverride,
      String proguardMaxHeapSize,
      Path generatedProGuardConfig,
      Set<Path> customProguardConfigs,
      SdkProguardType sdkProguardConfig,
      Optional<Integer> optimizationPasses,
      Map<Path, Path> inputAndOutputEntries,
      Set<Path> additionalLibraryJarsForProguard,
      Path proguardDirectory,
      BuildableContext buildableContext,
      ImmutableList.Builder<Step> steps) {

    Path pathToProGuardCommandLineArgsFile = proguardDirectory.resolve("command-line.txt");

    CommandLineHelperStep commandLineHelperStep = new CommandLineHelperStep(
        generatedProGuardConfig,
        customProguardConfigs,
        sdkProguardConfig,
        optimizationPasses,
        inputAndOutputEntries,
        additionalLibraryJarsForProguard,
        proguardDirectory,
        pathToProGuardCommandLineArgsFile);

    ProGuardObfuscateStep proGuardStep = new ProGuardObfuscateStep(
        inputAndOutputEntries,
        pathToProGuardCommandLineArgsFile,
        proguardJarOverride,
        proguardMaxHeapSize);

    buildableContext.recordArtifact(commandLineHelperStep.getConfigurationTxt());
    buildableContext.recordArtifact(commandLineHelperStep.getMappingTxt());

    steps.add(
        commandLineHelperStep,
        proGuardStep,
        // Some proguard configs can propagate the "-dontobfuscate" flag which disables
        // obfuscation and prevents the mapping.txt file from being generated.  So touch it
        // here to guarantee it's around when we go to cache this rule.
        new TouchStep(commandLineHelperStep.getMappingTxt()));
  }

  /**
   * @param inputAndOutputEntries Map of input/output pairs to proguard. The key represents an
   *     input jar (-injars); the value an output jar (-outjars).
   * @param pathToProGuardCommandLineArgsFile Path to file containing arguments to ProGuard.
   */
  private ProGuardObfuscateStep(
      Map<Path, Path> inputAndOutputEntries,
      Path pathToProGuardCommandLineArgsFile,
      Optional<Path> proguardJarOverride,
      String proguardMaxHeapSize) {
    this.inputAndOutputEntries = ImmutableMap.copyOf(inputAndOutputEntries);
    this.pathToProGuardCommandLineArgsFile = pathToProGuardCommandLineArgsFile;
    this.proguardJarOverride = proguardJarOverride;
    this.proguardMaxHeapSize = proguardMaxHeapSize;
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
      proguardJar =
          context.getProjectFilesystem().getPathForRelativePath(proguardJarOverride.get());
    } else {
      AndroidPlatformTarget androidPlatformTarget = context.getAndroidPlatformTarget();
      proguardJar = androidPlatformTarget.getProguardJar();
    }

    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add("java")
        .add("-Xmx" + proguardMaxHeapSize)
        .add("-jar").add(proguardJar.toString())
        .add("@" + pathToProGuardCommandLineArgsFile);
    return args.build();
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    int exitCode = super.execute(context);

    // proguard has a peculiar behaviour when multiple -injars/outjars pairs are specified in which
    // any -injars that would have been fully stripped away will not produce their matching -outjars
    // as requested (so the file won't exist).  Our build steps are not sophisticated enough to
    // account for this and remove those entries from the classes to dex so we hack things here to
    // ensure that the files exist but are empty.
    if (exitCode == 0) {
      exitCode = ensureAllOutputsExist(context);
    }

    return exitCode;
  }

  private int ensureAllOutputsExist(ExecutionContext context) {
    for (Path outputJar : inputAndOutputEntries.values()) {
      File outputJarFile = outputJar.toFile();
      if (!outputJarFile.exists()) {
        try {
          createEmptyZip(outputJarFile);
        } catch (IOException e) {
          context.logError(e, "Error creating empty zip file at: %s.", outputJarFile);
          return 1;
        }
      }
    }
    return 0;
  }

  @VisibleForTesting
  static void createEmptyZip(File file) throws IOException {
    Files.createParentDirs(file);
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
    return Objects.equal(this.inputAndOutputEntries, that.inputAndOutputEntries) &&
        Objects.equal(this.pathToProGuardCommandLineArgsFile,
            that.pathToProGuardCommandLineArgsFile);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(inputAndOutputEntries, pathToProGuardCommandLineArgsFile);
  }

  /**
   * Helper class to run as a step before ProGuardObfuscateStep to write out the
   * command-line parameters to a file.  The ProGuardObfuscateStep references
   * this file when it runs using ProGuard's '@' syntax.  This allows for longer
   * command-lines than would otherwise be supported.
   */
  @VisibleForTesting
  static class CommandLineHelperStep extends AbstractExecutionStep {

    private final Path generatedProGuardConfig;
    private final Set<Path> customProguardConfigs;
    private final Map<Path, Path> inputAndOutputEntries;
    private final ImmutableSet<Path> additionalLibraryJarsForProguard;
    private final SdkProguardType sdkProguardConfig;
    private final Optional<Integer> optimizationPasses;
    private final Path proguardDirectory;
    private final Path pathToProGuardCommandLineArgsFile;

    /**
     * @param generatedProGuardConfig Proguard configuration as produced by aapt.
     * @param customProguardConfigs Main rule and its dependencies proguard configurations.
     * @param sdkProguardConfig Which proguard config from the Android SDK to use.
     * @param inputAndOutputEntries Map of input/output pairs to proguard.  The key represents an
     *     input jar (-injars); the value an output jar (-outjars).
     * @param additionalLibraryJarsForProguard Libraries that are not operated upon by proguard but
     *     needed to resolve symbols.
     * @param proguardDirectory Output directory for various proguard-generated meta artifacts.
     * @param pathToProGuardCommandLineArgsFile Path to file containing arguments to ProGuard.
     */
    private CommandLineHelperStep(
        Path generatedProGuardConfig,
        Set<Path> customProguardConfigs,
        SdkProguardType sdkProguardConfig,
        Optional<Integer> optimizationPasses,
        Map<Path, Path> inputAndOutputEntries,
        Set<Path> additionalLibraryJarsForProguard,
        Path proguardDirectory,
        Path pathToProGuardCommandLineArgsFile) {
      super("write_proguard_command_line_parameters");
      this.generatedProGuardConfig = generatedProGuardConfig;
      this.customProguardConfigs = ImmutableSet.copyOf(customProguardConfigs);
      this.sdkProguardConfig = sdkProguardConfig;
      this.optimizationPasses = optimizationPasses;
      this.inputAndOutputEntries = ImmutableMap.copyOf(inputAndOutputEntries);
      this.additionalLibraryJarsForProguard = ImmutableSet.copyOf(additionalLibraryJarsForProguard);
      this.proguardDirectory = proguardDirectory;
      this.pathToProGuardCommandLineArgsFile = pathToProGuardCommandLineArgsFile;
    }

    @Override
    public int execute(ExecutionContext context) {
      String proGuardArguments = Joiner.on('\n').join(getParameters(context));
      try {
        context.getProjectFilesystem().writeContentsToPath(
            proGuardArguments,
            pathToProGuardCommandLineArgsFile);
      } catch (IOException e) {
        context.logError(e,
            "Error writing ProGuard arguments to file: %s.",
            pathToProGuardCommandLineArgsFile);
        return 1;
      }

      return 0;
    }

    /** @return the list of arguments to pass to ProGuard. */
    @VisibleForTesting
    ImmutableList<String> getParameters(ExecutionContext context) {
      ImmutableList.Builder<String> args = ImmutableList.builder();
      AndroidPlatformTarget androidPlatformTarget = context.getAndroidPlatformTarget();

      // Relative paths should be interpreted relative to project directory root, not the
      // written parameters file.
      args.add("-basedirectory")
          .add(context.getProjectDirectoryRoot().toAbsolutePath().toString());

      // -include
      switch (sdkProguardConfig) {
        case OPTIMIZED:
          args.add("-include")
              .add(androidPlatformTarget.getOptimizedProguardConfig().toString());
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
      args.add("-include").add(generatedProGuardConfig.toString());

      // -injars and -outjars paired together for each input.
      for (Map.Entry<Path, Path> inputOutputEntry : inputAndOutputEntries.entrySet()) {
        args.add("-injars").add(inputOutputEntry.getKey().toString());
        args.add("-outjars").add(inputOutputEntry.getValue().toString());
      }

      // -libraryjars
      Iterable<Path> bootclasspathPaths = androidPlatformTarget.getBootclasspathEntries();
      Iterable<Path> libraryJars = Iterables.concat(bootclasspathPaths,
          additionalLibraryJarsForProguard);
      args.add("-libraryjars").add(Joiner.on(':').join(libraryJars));

      // -dump
      args.add("-printmapping").add(getMappingTxt().toString());
      args.add("-printconfiguration").add(getConfigurationTxt().toString());

      return args.build();
    }

    public Path getConfigurationTxt() {
      return proguardDirectory.resolve("configuration.txt");
    }

    public Path getMappingTxt() {
      return proguardDirectory.resolve("mapping.txt");
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof CommandLineHelperStep)) {
        return false;
      }
      CommandLineHelperStep that = (CommandLineHelperStep) obj;

      return
          Objects.equal(sdkProguardConfig, that.sdkProguardConfig) &&
          Objects.equal(additionalLibraryJarsForProguard,
              that.additionalLibraryJarsForProguard) &&
          Objects.equal(customProguardConfigs, that.customProguardConfigs) &&
          Objects.equal(generatedProGuardConfig, that.generatedProGuardConfig) &&
          Objects.equal(inputAndOutputEntries, that.inputAndOutputEntries) &&
          Objects.equal(proguardDirectory, that.proguardDirectory) &&
          Objects.equal(pathToProGuardCommandLineArgsFile, that.pathToProGuardCommandLineArgsFile);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(
          sdkProguardConfig,
          additionalLibraryJarsForProguard,
          customProguardConfigs,
          generatedProGuardConfig,
          inputAndOutputEntries,
          proguardDirectory,
          pathToProGuardCommandLineArgsFile);
    }
  }
}
