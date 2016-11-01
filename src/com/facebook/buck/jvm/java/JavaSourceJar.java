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

package com.facebook.buck.jvm.java;

import static com.facebook.buck.maven.AetherUtil.CLASSIFIER_SOURCES;
import static com.facebook.buck.maven.AetherUtil.addClassifier;
import static com.facebook.buck.zip.ZipCompressionLevel.DEFAULT_COMPRESSION_LEVEL;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.zip.ZipStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


public class JavaSourceJar extends AbstractBuildRule implements MavenPublishable, HasSources {

  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> sources;
  @AddToRuleKey
  private final Optional<String> mavenCoords;
  @AddToRuleKey
  private final Optional<SourcePath> mavenPomTemplate;
  @AddToRuleKey
  private final Iterable<HasMavenCoordinates> mavenDeps;

  private final Path output;
  private final Path temp;

  public JavaSourceJar(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Optional<String> mavenCoords,
      Optional<SourcePath> mavenPomTemplate,
      Iterable<HasMavenCoordinates> mavenDeps,
      ImmutableSortedSet<SourcePath> sources) {
    super(params, resolver);

    this.mavenCoords = mavenCoords.map(coord -> addClassifier(coord, CLASSIFIER_SOURCES));
    this.mavenPomTemplate = mavenPomTemplate;
    this.mavenDeps = mavenDeps;

    this.sources = sources;
    BuildTarget target = params.getBuildTarget();
    this.output =
        BuildTargets.getGenPath(
            getProjectFilesystem(),
            target,
            "%s" + Javac.SRC_JAR);
    this.temp = BuildTargets.getScratchPath(getProjectFilesystem(), target, "%s-srcs");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(new MkdirStep(getProjectFilesystem(), output.getParent()));
    steps.add(new RmStep(getProjectFilesystem(), output, /* force deletion */ true));
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), temp));

    Set<Path> seenPackages = Sets.newHashSet();
    Map<ProjectFilesystem, JavaPackageFinder> finders = new HashMap<>();

    for (SourcePath sourcePath : sources) {
      Path source = getResolver().getAbsolutePath(sourcePath);
      if (!"java".equals(MorePaths.getFileExtension(source))) {
        // Only interested in source files.
        continue;
      }

      ProjectFilesystem filesystem = getResolver().getFilesystem(sourcePath);
      JavaPackageFinder finder = finders.computeIfAbsent(
          filesystem,
          SourceReadingPackageFinder::new);

      Path packageFolder = finder.findJavaPackageFolder(source);
      Path packageDir = temp.resolve(packageFolder);
      if (seenPackages.add(packageDir)) {
        steps.add(new MkdirStep(getProjectFilesystem(), packageDir));
      }
      steps.add(
          CopyStep.forFile(
              getProjectFilesystem(),
              source,
              packageDir.resolve(source.getFileName())));
    }
    steps.add(
        new ZipStep(
            getProjectFilesystem(),
            output,
            ImmutableSet.of(),
            /* junk paths */ false,
            DEFAULT_COMPRESSION_LEVEL,
            temp));

    buildableContext.recordArtifact(output);

    return steps.build();
  }

  @Override
  public ImmutableSortedSet<SourcePath> getSources() {
    return sources;
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

  @Override
  public Optional<String> getMavenCoords() {
    return mavenCoords;
  }

  @Override
  public Iterable<HasMavenCoordinates> getMavenDeps() {
    return mavenDeps;
  }

  @Override
  public Iterable<BuildRule> getPackagedDependencies() {
    return ImmutableSet.of(this);
  }

  @Override
  public Optional<Path> getPomTemplate() {
    return mavenPomTemplate.map(getResolver()::getAbsolutePath);
  }

}
