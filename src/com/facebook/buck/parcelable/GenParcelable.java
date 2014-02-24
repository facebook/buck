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

import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SrcsAttributeBuilder;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.BuckConstant;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

public class GenParcelable extends AbstractBuildable {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(ANDROID);

  private final ImmutableSortedSet<Path> srcs;
  private final String outputDirectory;

  private GenParcelable(BuildRuleParams buildRuleParams,
                        Set<Path> srcs) {
    this.srcs = ImmutableSortedSet.copyOf(srcs);

    this.outputDirectory = String.format("%s/%s/__%s__",
        BuckConstant.GEN_DIR,
        buildRuleParams.getBuildTarget().getBasePath(),
        buildRuleParams.getBuildTarget().getShortName());
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    return srcs;
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
      throws IOException {
    Step step = new Step() {

      @Override
      public int execute(ExecutionContext context) {
        for (Path src : srcs) {
          try {
            // Generate the Java code for the Parcelable class.
            ParcelableClass parcelableClass = Parser.parse(src.toFile());
            String generatedJava = new Generator(parcelableClass).generate();

            // Write the generated Java code to a file.
            File outputPath = new File(getOutputPathForParcelableClass(parcelableClass));
            Files.createParentDirs(outputPath);
            Files.write(generatedJava, outputPath, Charsets.UTF_8);
          } catch (IOException e) {
            e.printStackTrace(context.getStdErr());
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
  private String getOutputPathForParcelableClass(ParcelableClass parcelableClass) {
    return outputDirectory + '/'
        + parcelableClass.getPackageName().replace('.', '/') + '/'
        + parcelableClass.getClassName() + ".java";
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) throws IOException {
    return builder
        .set("outputDirectory", outputDirectory);
  }

  public static Builder newGenParcelableRuleBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildable.Builder implements
      SrcsAttributeBuilder {

    private ImmutableSortedSet.Builder<Path> srcs = ImmutableSortedSet.naturalOrder();

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public Builder addSrc(Path relativePathToSrc) {
      srcs.add(relativePathToSrc);
      return this;
    }

    @Override
    public BuildRuleType getType() {
      return BuildRuleType.GEN_PARCELABLE;
    }

    @Override
    protected GenParcelable newBuildable(BuildRuleParams params, BuildRuleResolver resolver) {
      return new GenParcelable(params, srcs.build());
    }
  }
}
