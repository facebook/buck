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

package com.facebook.buck.parcelable;

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Files;

import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import javax.annotation.Nullable;

public class GenParcelable extends AbstractBuildRule {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(ANDROID);

  private final ImmutableSortedSet<SourcePath> srcs;
  private final Path outputDirectory;

  GenParcelable(BuildRuleParams params, SourcePathResolver resolver, Set<SourcePath> srcs) {
    super(params, resolver);
    this.srcs = ImmutableSortedSet.copyOf(srcs);
    this.outputDirectory = BuildTargets.getGenPath(params.getBuildTarget(), "__%s__");
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return getResolver().filterInputsToCompareToOutput(srcs);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      final BuildContext buildContext,
      BuildableContext buildableContext) {
    Step step = new Step() {

      @Override
      public int execute(ExecutionContext executionContext) {
        for (SourcePath sourcePath : srcs) {
          Path src = getResolver().getPath(sourcePath);
          Path file = executionContext.getProjectFilesystem().getPathForRelativePath(src);
          try {
            // Generate the Java code for the Parcelable class.
            ParcelableClass parcelableClass = Parser.parse(file);
            String generatedJava = new Generator(parcelableClass).generate();

            // Write the generated Java code to a file.
            File outputPath = getOutputPathForParcelableClass(parcelableClass).toFile();
            Files.createParentDirs(outputPath);
            Files.write(generatedJava, outputPath, Charsets.UTF_8);
          } catch (IOException | SAXException e) {
            executionContext.logError(e, "Error creating parcelable from file: %s", src);
            return 1;
          }
        }
        return 0;
      }

      @Override
      public String getShortName() {
        return "gen_parcelable";
      }

      @Override
      public String getDescription(ExecutionContext context) {
        return "gen_parcelable";
      }};

    return ImmutableList.of(step);
  }

  @VisibleForTesting
  private Path getOutputPathForParcelableClass(ParcelableClass parcelableClass) {
    return outputDirectory
        .resolve(parcelableClass.getPackageName().replace('.', '/'))
        .resolve(parcelableClass.getClassName() + ".java");
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder.setReflectively("outputDirectory", outputDirectory.toString());
  }
}
