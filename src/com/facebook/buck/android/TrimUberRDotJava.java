/*
 * Copyright 2016-present Facebook, Inc.
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

import com.android.common.annotations.NonNull;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.zip.CustomZipOutputStream;
import com.facebook.buck.zip.ZipOutputStreams;
import com.facebook.buck.zip.ZipScrubberStep;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * Rule for trimming unnecessary ids from R.java files.
 */
class TrimUberRDotJava extends AbstractBuildRule {
  private final AaptPackageResources aaptPackageResources;
  private final Collection<DexProducedFromJavaLibrary> allPreDexRules;
  private final Optional<String> keepResourcePattern;

  private static final Pattern R_DOT_JAVA_LINE_PATTERN = Pattern.compile(
      "^ *public static final int(?:\\[\\])? (\\w+)=");

  private static final Pattern R_DOT_JAVA_PACKAGE_NAME_PATTERN = Pattern.compile(
      "^ *package ([\\w.]+);");

  TrimUberRDotJava(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      AaptPackageResources aaptPackageResources,
      Collection<DexProducedFromJavaLibrary> allPreDexRules,
      Optional<String> keepResourcePattern) {
    super(buildRuleParams, resolver);
    this.aaptPackageResources = aaptPackageResources;
    this.allPreDexRules = allPreDexRules;
    this.keepResourcePattern = keepResourcePattern;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(getPathToOutput());
    return ImmutableList.of(
        new MakeCleanDirectoryStep(getProjectFilesystem(), getPathToOutput().getParent()),
        new PerformTrimStep(),
        new ZipScrubberStep(getProjectFilesystem(), getPathToOutput())
    );
  }

  @Override
  public Path getPathToOutput() {
    return BuildTargets.getGenPath(
        getProjectFilesystem(),
        getBuildTarget(),
        "/%s/_trimmed_r_dot_java.src.zip");
  }

  private class PerformTrimStep implements Step {
    @Override
    public StepExecutionResult execute(ExecutionContext context)
        throws IOException, InterruptedException {
      ImmutableSet.Builder<String> allReferencedResourcesBuilder = ImmutableSet.builder();
      for (DexProducedFromJavaLibrary preDexRule : allPreDexRules) {
        Optional<ImmutableList<String>> referencedResources = preDexRule.getReferencedResources();
        if (referencedResources.isPresent()) {
          allReferencedResourcesBuilder.addAll(referencedResources.get());
        }
      }
      final ImmutableSet<String> allReferencedResources = allReferencedResourcesBuilder.build();

      final ProjectFilesystem projectFilesystem = getProjectFilesystem();
      final Path sourceDir = aaptPackageResources.getPathToGeneratedRDotJavaSrcFiles();
      try (final CustomZipOutputStream output =
               ZipOutputStreams.newOutputStream(projectFilesystem.resolve(getPathToOutput()))) {
        if (!projectFilesystem.exists(sourceDir)) {
          // dx fails if its input contains no classes.  Rather than add empty input handling
          // to DxStep, the dex merger, and every other step of this chain, just generate a
          // stub class.  This will be stripped by ProGuard in release builds and have a minimal
          // effect on debug builds.
          output.putNextEntry(new ZipEntry("com/facebook/buck/AppWithoutResourcesStub.java"));
          output.write((
              "package com.facebook.buck_generated;\n" +
              "final class AppWithoutResourcesStub {}"
              ).getBytes());
        } else {
          projectFilesystem.walkRelativeFileTree(
              sourceDir,
              new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                  if (attrs.isDirectory()) {
                    return FileVisitResult.CONTINUE;
                  }
                  if (!attrs.isRegularFile()) {
                    throw new RuntimeException(String.format(
                        "Found unknown file type while looking for R.java: %s (%s)",
                        file,
                        attrs));
                  }
                  if (!file.getFileName().toString().endsWith(".java")) {
                    throw new RuntimeException(String.format(
                        "Found unknown file while looking for R.java: %s",
                        file));
                  }

                  output.putNextEntry(new ZipEntry(
                      MorePaths.pathWithUnixSeparators(sourceDir.relativize(file))));
                  if (allPreDexRules.isEmpty()) {
                    // If there are no pre-dexed inputs, we don't yet support trimming
                    // R.java, so just copy it verbatim (instead of trimming it down to nothing).
                    projectFilesystem.copyToOutputStream(file, output);
                  } else {
                    filterRDotJava(
                        projectFilesystem.readLines(file),
                        output,
                        allReferencedResources,
                        keepResourcePattern);
                  }
                  return FileVisitResult.CONTINUE;
                }
              });
        }
      }
      return StepExecutionResult.SUCCESS;
    }

    @Override
    public String getShortName() {
      return "trim_uber_r_dot_java";
    }

    @Override
    public String getDescription(ExecutionContext context) {
      return String.format(
          "trim_uber_r_dot_java %s > %s",
          aaptPackageResources.getPathToGeneratedRDotJavaSrcFiles(),
          getPathToOutput());
    }
  }

  private static void filterRDotJava(
      List<String> rDotJavaLines,
      OutputStream output,
      ImmutableSet<String> allReferencedResources,
      Optional<String> keepResourcePattern)
      throws IOException {
    String packageName = null;
    Matcher m;

    Optional<Pattern> keepPattern = keepResourcePattern.transform(new Function<String, Pattern>() {
      @NonNull
      @Override
      public Pattern apply(@NonNull String input) {
        return Pattern.compile(input);
      }
    });

    for (String line : rDotJavaLines) {
      if (packageName == null) {
        m = R_DOT_JAVA_PACKAGE_NAME_PATTERN.matcher(line);
        if (m.find()) {
          packageName = m.group(1);
        } else {
          continue;
        }
      }
      m = R_DOT_JAVA_LINE_PATTERN.matcher(line);
      // We match on the package name + resource name.
      // This can cause us to keep (for example) R.layout.foo when only R.string.foo
      // is referenced.  That is a very rare case, though, and not worth the complexity to fix.
      if (m.find()) {
        final String resource = m.group(1);
        boolean shouldWriteLine = allReferencedResources.contains(packageName + "." + resource) ||
            (keepPattern.isPresent() && keepPattern.get().matcher(resource).find());
        if (!shouldWriteLine) {
          continue;
        }
      }
      output.write(line.getBytes(Charsets.UTF_8));
      output.write('\n');
    }
  }
}
