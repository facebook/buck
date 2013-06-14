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

package com.facebook.buck.java;

import com.facebook.buck.model.AnnotationProcessingData;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AbstractCachingBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleBuilderParams;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.Step;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.util.List;

/**
 * A rule that establishes a pre-compiled JAR file as a dependency.
 */
public class PrebuiltJarRule extends AbstractCachingBuildRule
    implements JavaLibraryRule, HasClasspathEntries {

  private final String binaryJar;
  private final Optional<String> sourceJar;
  private final Optional<String> javadocUrl;
  private final Supplier<ImmutableSetMultimap<BuildRule, String>>
      transitiveClasspathEntriesSupplier;

  private final Supplier<ImmutableSetMultimap<BuildRule, String>>
      declaredClasspathEntriesSupplier;

  PrebuiltJarRule(BuildRuleParams buildRuleParams,
      String classesJar,
      Optional<String> sourceJar,
      Optional<String> javadocUrl) {
    super(buildRuleParams);
    this.binaryJar = Preconditions.checkNotNull(classesJar);
    this.sourceJar = Preconditions.checkNotNull(sourceJar);
    this.javadocUrl = Preconditions.checkNotNull(javadocUrl);

    transitiveClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<BuildRule, String>>() {
          @Override
          public ImmutableSetMultimap<BuildRule, String> get() {
            ImmutableSetMultimap.Builder<BuildRule, String> classpathEntries =
                ImmutableSetMultimap.builder();
            classpathEntries.put(PrebuiltJarRule.this, getBinaryJar());
            classpathEntries.putAll(Classpaths.getClasspathEntries(getDeps()));
            return classpathEntries.build();
          }
        });

    declaredClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<BuildRule, String>>() {
          @Override
          public ImmutableSetMultimap<BuildRule, String> get() {
            ImmutableSetMultimap.Builder<BuildRule, String> classpathEntries =
                ImmutableSetMultimap.builder();
            classpathEntries.put(PrebuiltJarRule.this, getBinaryJar());
            return classpathEntries.build();
          }
        });
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.PREBUILT_JAR;
  }

  public String getBinaryJar() {
    return binaryJar;
  }

  public Optional<String> getSourceJar() {
    return sourceJar;
  }

  public Optional<String> getJavadocUrl() {
    return javadocUrl;
  }

  @Override
  protected Iterable<String> getInputsToCompareToOutput(BuildContext context) {
    return ImmutableList.of(getBinaryJar());
  }

  @Override
  public ImmutableSetMultimap<BuildRule, String> getTransitiveClasspathEntries() {
    return transitiveClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSetMultimap<BuildRule, String> getDeclaredClasspathEntries() {
    return declaredClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSet<String> getOutputClasspathEntries() {
    return ImmutableSet.of(getBinaryJar());
  }

  @Override
  public ImmutableSortedSet<String> getJavaSrcs() {
    return ImmutableSortedSet.of();
  }

  @Override
  public AnnotationProcessingData getAnnotationProcessingData() {
    return AnnotationProcessingData.EMPTY;
  }

  @Override
  protected List<Step> buildInternal(BuildContext context)
      throws IOException {
    return ImmutableList.of();
  }

  @Override
  public String getPathToOutputFile() {
    return getBinaryJar();
  }

  @Override
  public boolean isLibrary() {
    return true;
  }

  @Override
  protected RuleKey.Builder ruleKeyBuilder() {
    return super.ruleKeyBuilder()
        .set("binaryJar", binaryJar)
        .set("sourceJar", sourceJar)
        .set("javadocUrl", javadocUrl);
  }

  public static Builder newPrebuiltJarRuleBuilder() {
    return new Builder();
  }

  public static class Builder extends AbstractBuildRuleBuilder<PrebuiltJarRule> {

    private String binaryJar;
    private Optional<String> sourceJar = Optional.absent();
    private Optional<String> javadocUrl = Optional.absent();

    private Builder() {}

    @Override
    public PrebuiltJarRule build(BuildRuleBuilderParams buildRuleBuilderParams) {
      return new PrebuiltJarRule(createBuildRuleParams(buildRuleBuilderParams),
          binaryJar,
          sourceJar,
          javadocUrl);
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    @Override
    public Builder addDep(String dep) {
      super.addDep(dep);
      return this;
    }

    @Override
    public Builder addVisibilityPattern(BuildTargetPattern visibilityPattern) {
      super.addVisibilityPattern(visibilityPattern);
      return this;
    }

    public Builder setBinaryJar(String binaryJar) {
      this.binaryJar = binaryJar;
      return this;
    }

    public Builder setSourceJar(Optional<String> sourceJar) {
      this.sourceJar = Preconditions.checkNotNull(sourceJar);
      return this;
    }

    public Builder setJavadocUrl(Optional<String> javadocUrl) {
      this.javadocUrl = Preconditions.checkNotNull(javadocUrl);
      return this;
    }

  }
}
