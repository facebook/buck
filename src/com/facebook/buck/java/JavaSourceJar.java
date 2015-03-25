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

package com.facebook.buck.java;

import static com.facebook.buck.zip.ZipStep.DEFAULT_COMPRESSION_LEVEL;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.zip.ZipStep;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Set;


public class JavaSourceJar extends AbstractBuildRule {
  public static final BuildRuleType TYPE = BuildRuleType.of("source_jar");

  private final ImmutableSortedSet<SourcePath> sources;
  private final Path output;
  private final Path temp;

  public JavaSourceJar(
      BuildRuleParams params,
      SourcePathResolver resolver,
      ImmutableSortedSet<SourcePath> sources) {
    super(params, resolver);
    this.sources = sources;
    BuildTarget target = params.getBuildTarget();
    this.output = BuildTargets.getGenPath(target, String.format("%%s%s", Javac.SRC_ZIP));
    this.temp = BuildTargets.getScratchPath(target, "%s-srcs");
  }

  @Override
  protected ImmutableCollection<Path> getInputsToCompareToOutput() {
    return getResolver().filterInputsToCompareToOutput(sources);
  }

  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    JavaPackageFinder packageFinder = context.getJavaPackageFinder();

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(new MkdirStep(output.getParent()));
    steps.add(new RmStep(output, /* force deletion */ true));
    steps.add(new MakeCleanDirectoryStep(temp));

    Set<Path> seenPackages = Sets.newHashSet();

    // We only want to consider raw source files, since the java package finder doesn't have the
    // smarts to read the "package" line from a source file.

    for (Path source : getResolver().filterInputsToCompareToOutput(sources)) {
      Path packageFolder = packageFinder.findJavaPackageFolder(source);
      Path packageDir = temp.resolve(packageFolder);
      if (seenPackages.add(packageDir)) {
        steps.add(new MkdirStep(packageDir));
      }
      steps.add(CopyStep.forFile(source, packageDir.resolve(source.getFileName())));
    }
    steps.add(new ZipStep(
            output,
            ImmutableSet.<Path>of(),
            /* junk paths */ false,
            DEFAULT_COMPRESSION_LEVEL,
            temp));

    buildableContext.recordArtifact(output);

    return steps.build();
  }

  @Override
  public Path getPathToOutputFile() {
    return output;
  }
}
