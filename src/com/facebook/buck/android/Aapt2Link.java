/*
 * Copyright 2015-present Facebook, Inc.
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

import com.android.annotations.VisibleForTesting;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.zip.ZipScrubberStep;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** Perform the "aapt2 link" step of building an Android app. */
public class Aapt2Link extends AbstractBuildRule {
  @AddToRuleKey private final ImmutableList<Aapt2Compile> compileRules;
  @AddToRuleKey private final SourcePath manifest;
  @AddToRuleKey private final ManifestEntries manifestEntries;

  Aapt2Link(
      BuildRuleParams buildRuleParams,
      SourcePathRuleFinder ruleFinder,
      ImmutableList<Aapt2Compile> compileRules,
      ImmutableList<HasAndroidResourceDeps> resourceRules,
      SourcePath manifest,
      ManifestEntries manifestEntries) {
    super(
        buildRuleParams.copyReplacingDeclaredAndExtraDeps(
            Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(compileRules)
                    .addAll(RichStream.from(resourceRules).filter(BuildRule.class).toOnceIterable())
                    .addAll(ruleFinder.filterBuildRuleInputs(manifest))
                    .build()),
            Suppliers.ofInstance(ImmutableSortedSet.of())));
    this.compileRules = compileRules;
    this.manifest = manifest;
    this.manifestEntries = manifestEntries;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.addAll(
        MakeCleanDirectoryStep.of(getProjectFilesystem(), getResourceApkPath().getParent()));

    AaptPackageResources.prepareManifestForAapt(
        steps,
        getProjectFilesystem(),
        getFinalManifestPath(),
        context.getSourcePathResolver().getAbsolutePath(manifest),
        manifestEntries);

    steps.add(
        new Aapt2LinkStep(
            getProjectFilesystem().getRootPath(),
            compileRules
                .stream()
                .map(Aapt2Compile::getSourcePathToOutput)
                .map(context.getSourcePathResolver()::getAbsolutePath)));
    steps.add(ZipScrubberStep.of(getProjectFilesystem().resolve(getResourceApkPath())));

    steps.add(
        new MakeRDotTxtStep(getProjectFilesystem(), getInitialRDotJavaDir(), getRDotTxtPath()));

    buildableContext.recordArtifact(getFinalManifestPath());
    buildableContext.recordArtifact(getResourceApkPath());
    buildableContext.recordArtifact(getProguardConfigPath());
    buildableContext.recordArtifact(getRDotTxtPath());
    // Don't really need this, but it's small and might help with debugging.
    buildableContext.recordArtifact(getInitialRDotJavaDir());

    return steps.build();
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }

  private Path getFinalManifestPath() {
    return BuildTargets.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "%s/AndroidManifest.xml");
  }

  private Path getResourceApkPath() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s/resource-apk.ap_");
  }

  private Path getProguardConfigPath() {
    return BuildTargets.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "%s/proguard-for-resources.pro");
  }

  private Path getRDotTxtPath() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s/R.txt");
  }

  /** Directory containing R.java files produced by aapt2 link. */
  private Path getInitialRDotJavaDir() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s/initial-rdotjava");
  }

  public AaptOutputInfo getAaptOutputInfo() {
    return AaptOutputInfo.builder()
        .setPathToRDotTxt(new ExplicitBuildTargetSourcePath(getBuildTarget(), getRDotTxtPath()))
        .setPrimaryResourcesApkPath(
            new ExplicitBuildTargetSourcePath(getBuildTarget(), getResourceApkPath()))
        .setAndroidManifestXml(
            new ExplicitBuildTargetSourcePath(getBuildTarget(), getFinalManifestPath()))
        .setAaptGeneratedProguardConfigFile(
            new ExplicitBuildTargetSourcePath(getBuildTarget(), getProguardConfigPath()))
        .build();
  }

  class Aapt2LinkStep extends ShellStep {
    private final Stream<Path> compiledResourcePaths;

    Aapt2LinkStep(Path workingDirectory, Stream<Path> compiledResourcePaths) {
      super(workingDirectory);
      this.compiledResourcePaths = compiledResourcePaths;
    }

    @Override
    public String getShortName() {
      return "aapt2_link";
    }

    @Override
    protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
      AndroidPlatformTarget androidPlatformTarget = context.getAndroidPlatformTarget();

      ImmutableList.Builder<String> builder = ImmutableList.builder();

      builder.add(androidPlatformTarget.getAapt2Executable().toString());
      builder.add("link");
      if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
        builder.add("-v");
      }

      builder.add("--no-auto-version");
      builder.add("--auto-add-overlay");

      builder.add("-o", getResourceApkPath().toString());
      builder.add("--proguard", getProguardConfigPath().toString());
      builder.add("--manifest", getFinalManifestPath().toString());
      builder.add("-I", androidPlatformTarget.getAndroidJar().toString());
      builder.add("--java", getInitialRDotJavaDir().toString());
      // Generate a custom-package R.java just for the purpose of generating R.txt.
      builder.add("--custom-package", "make.r.txt");

      compiledResourcePaths.forEach(r -> builder.add("-R", r.toString()));

      return builder.build();
    }
  }

  @VisibleForTesting
  static class MakeRDotTxtStep implements Step {
    private static final Pattern NO_OP_LINE =
        Pattern.compile(
            "^package |"
                + // Don't care about package.
                "^public final class R \\{|"
                + // Don't care about top-level class.
                "^[/*]" // Comment.
            );
    private static final Pattern CLASS_LINE =
        Pattern.compile("public static final class (\\w+) \\{");
    private static final Pattern INT_LINE =
        Pattern.compile("public static final int (\\w+)=(\\w+);");
    private static final Pattern ARRAY_LINE =
        Pattern.compile("public static final int\\[\\] (\\w+)=\\{");
    private static final Pattern VALUES_LINE = Pattern.compile("[x0-9a-f, ]*");

    private final ProjectFilesystem projectFilesystem;
    private final Path initialRDotJavaDir;
    private final Path rDotTxtPath;

    public MakeRDotTxtStep(
        ProjectFilesystem projectFilesystem, Path initialRDotJavaDir, Path rDotTxtPath) {
      this.projectFilesystem = projectFilesystem;
      this.initialRDotJavaDir = initialRDotJavaDir;
      this.rDotTxtPath = rDotTxtPath;
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context)
        throws IOException, InterruptedException {
      List<String> javaLines =
          projectFilesystem.readLines(initialRDotJavaDir.resolve("make/r/txt/R.java"));
      List<String> txtLines = convertLines(javaLines);
      projectFilesystem.writeLinesToPath(txtLines, rDotTxtPath);
      return StepExecutionResult.SUCCESS;
    }

    @VisibleForTesting
    static List<String> convertLines(List<String> javaLines) {
      ImmutableList.Builder<String> txtLines = ImmutableList.builder();

      @Nullable String inClass = null;
      @Nullable String currentArrayName = null;
      @Nullable List<String> currentArrayValues = null;

      for (String line : javaLines) {
        line = line.trim();

        if (line.isEmpty() || NO_OP_LINE.matcher(line).lookingAt()) {
          continue;
        }

        if (currentArrayName != null) {

          if (line.equals("};")) {
            txtLines.add(
                String.format(
                    "int[] %s %s { %s }",
                    inClass, currentArrayName, Joiner.on(',').join(currentArrayValues)));
            currentArrayName = null;
            currentArrayValues = null;
            continue;
          }

          if (VALUES_LINE.matcher(line).matches()) {
            RichStream.of(line.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(currentArrayValues::add);
            continue;
          }

          throw new IllegalArgumentException("Got unexpected line in array data: " + line);
        }

        Matcher classMatch = CLASS_LINE.matcher(line);
        if (classMatch.matches()) {
          inClass = classMatch.group(1);
          continue;
        }

        if (line.equals("}")) {
          inClass = null;
          continue;
        }

        if (inClass == null) {
          throw new IllegalArgumentException("Got unexpected line with no active class: " + line);
        }

        Matcher intMatch = INT_LINE.matcher(line);
        if (intMatch.matches()) {
          txtLines.add(
              String.format("int %s %s %s", inClass, intMatch.group(1), intMatch.group(2)));
          continue;
        }

        Matcher arrayMatch = ARRAY_LINE.matcher(line);
        if (arrayMatch.matches()) {
          currentArrayName = arrayMatch.group(1);
          currentArrayValues = new ArrayList<>();
          continue;
        }

        throw new IllegalArgumentException("Got unexpected line: " + line);
      }

      return txtLines.build();
    }

    public static void main(String[] args) throws IOException {
      List<String> javaLines = Files.readAllLines(Paths.get(args[0]), Charsets.UTF_8);
      List<String> txtLines = convertLines(javaLines);
      txtLines.forEach(System.out::println);
    }

    @Override
    public String getShortName() {
      return "make_r_dot_txt";
    }

    @Override
    public String getDescription(ExecutionContext context) {
      return String.format("make_r_dot_txt %s > %s", initialRDotJavaDir, rDotTxtPath);
    }
  }
}
