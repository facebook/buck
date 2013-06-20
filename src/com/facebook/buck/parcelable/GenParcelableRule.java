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

import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AbstractCachingBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
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
import java.util.List;
import java.util.Set;

public class GenParcelableRule extends AbstractCachingBuildRule {

  private final ImmutableSortedSet<String> srcs;
  private final String outputDirectory;

  private GenParcelableRule(BuildRuleParams buildRuleParams,
      Set<String> srcs) {
    super(buildRuleParams);
    this.srcs = ImmutableSortedSet.copyOf(srcs);

    this.outputDirectory = String.format("%s/%s/__%s__",
        BuckConstant.GEN_DIR,
        buildRuleParams.getBuildTarget().getBasePath(),
        buildRuleParams.getBuildTarget().getShortName());
  }

  @Override
  protected Iterable<String> getInputsToCompareToOutput() {
    return srcs;
  }

  @Override
  protected List<Step> buildInternal(BuildContext context)
      throws IOException {
    Step step = new Step() {

      @Override
      public int execute(ExecutionContext context) {
        for (String src : srcs) {
          try {
            // Generate the Java code for the Parcelable class.
            ParcelableClass parcelableClass = Parser.parse(new File(src));
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
      public String getShortName(ExecutionContext context) {
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
  public BuildRuleType getType() {
    return BuildRuleType.GEN_PARCELABLE;
  }

  @Override
  public boolean isAndroidRule() {
    return true;
  }

  @Override
  protected RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    return super.appendToRuleKey(builder)
        .set("srcs", srcs)
        .set("outputDirectory", outputDirectory);
  }

  public static Builder newGenParcelableRuleBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildRuleBuilder<GenParcelableRule> implements
      SrcsAttributeBuilder {

    private ImmutableSortedSet.Builder<String> srcs = ImmutableSortedSet.naturalOrder();

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public Builder addSrc(String relativePathToSrc) {
      srcs.add(relativePathToSrc);
      return this;
    }

    @Override
    public GenParcelableRule build(BuildRuleResolver ruleResolver) {
      BuildRuleParams buildRuleParams = createBuildRuleParams(ruleResolver);
      return new GenParcelableRule(buildRuleParams, srcs.build());
    }
  }
}
