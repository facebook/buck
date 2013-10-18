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

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AnnotationProcessingData;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.DoNotUseAbstractBuildable;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.common.io.InputSupplier;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import javax.annotation.Nullable;

/**
 * A rule that establishes a pre-compiled JAR file as a dependency.
 */
public class PrebuiltJarRule extends DoNotUseAbstractBuildable
    implements JavaLibraryRule, HasClasspathEntries {

  private final static BuildableProperties OUTPUT_TYPE = new BuildableProperties(LIBRARY);

  private final String binaryJar;
  private final Optional<String> sourceJar;
  private final Optional<String> javadocUrl;
  private final Supplier<ImmutableSetMultimap<JavaLibraryRule, String>>
      transitiveClasspathEntriesSupplier;

  private final Supplier<ImmutableSetMultimap<JavaLibraryRule, String>>
      declaredClasspathEntriesSupplier;

  /**
   * This will be set either by executing the build steps, or by
   * {@link #initializeFromDisk(OnDiskBuildInfo)}.
   */
  @Nullable
  private Sha1HashCode abiKey;

  PrebuiltJarRule(BuildRuleParams buildRuleParams,
      String classesJar,
      Optional<String> sourceJar,
      Optional<String> javadocUrl) {
    super(buildRuleParams);
    this.binaryJar = Preconditions.checkNotNull(classesJar);
    this.sourceJar = Preconditions.checkNotNull(sourceJar);
    this.javadocUrl = Preconditions.checkNotNull(javadocUrl);

    transitiveClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<JavaLibraryRule, String>>() {
          @Override
          public ImmutableSetMultimap<JavaLibraryRule, String> get() {
            ImmutableSetMultimap.Builder<JavaLibraryRule, String> classpathEntries =
                ImmutableSetMultimap.builder();
            classpathEntries.put(PrebuiltJarRule.this, getBinaryJar());
            classpathEntries.putAll(Classpaths.getClasspathEntries(getDeps()));
            return classpathEntries.build();
          }
        });

    declaredClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<JavaLibraryRule, String>>() {
          @Override
          public ImmutableSetMultimap<JavaLibraryRule, String> get() {
            ImmutableSetMultimap.Builder<JavaLibraryRule, String> classpathEntries =
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

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
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
  public Iterable<String> getInputsToCompareToOutput() {
    return ImmutableList.of(getBinaryJar());
  }

  @Override
  public Sha1HashCode getAbiKey() {
    Preconditions.checkNotNull(abiKey,
        "%s must be built before its ABI key can be returned.",
        this);
    return abiKey;
  }

  @VisibleForTesting
  void setAbiKey(Sha1HashCode abiKey) {
    this.abiKey = Preconditions.checkNotNull(abiKey);
  }

  @Override
  public void initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    Optional<Sha1HashCode> abiKeyHash = onDiskBuildInfo.getHash(AbiRule.ABI_KEY_ON_DISK_METADATA);
    if (abiKeyHash.isPresent()) {
      abiKey = abiKeyHash.get();
    } else {
      throw new IllegalStateException(String.format(
          "Should not be initializing %s from disk if the ABI key is not written.", this));
    }
  }

  @Override
  public ImmutableSetMultimap<JavaLibraryRule, String> getTransitiveClasspathEntries() {
    return transitiveClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSetMultimap<JavaLibraryRule, String> getDeclaredClasspathEntries() {
    return declaredClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSetMultimap<JavaLibraryRule, String> getOutputClasspathEntries() {
    return ImmutableSetMultimap.<JavaLibraryRule, String>builder().put(this, getBinaryJar()).build();
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
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
      throws IOException {
    // Create a step to compute the ABI key.
    Step calculateAbiStep = new CalculateAbiStep(buildableContext);
    return ImmutableList.of(calculateAbiStep);
  }

  private class CalculateAbiStep extends AbstractExecutionStep {

    private final BuildableContext buildableContext;

    private CalculateAbiStep(BuildableContext buildableContext) {
      super("calculate ABI for " + binaryJar);
      this.buildableContext = Preconditions.checkNotNull(buildableContext);
    }

    @Override
    public int execute(ExecutionContext context) {
      File jarFile = context.getProjectFilesystem().getFileForRelativePath(binaryJar);
      InputSupplier<? extends InputStream> inputSupplier = Files.newInputStreamSupplier(jarFile);
      HashCode fileSha1;
      try {
        fileSha1 = ByteStreams.hash(inputSupplier, Hashing.sha1());
      } catch (IOException e) {
        context.logError(e, "Failed to calculate ABI for %s.", binaryJar);
        return 1;
      }

      abiKey = new Sha1HashCode(fileSha1.toString());
      buildableContext.addMetadata(AbiRule.ABI_KEY_ON_DISK_METADATA, abiKey.getHash());

      return 0;
    }

  }

  @Override
  public String getPathToOutputFile() {
    return getBinaryJar();
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) throws IOException {
    return super.appendToRuleKey(builder)
        .set("binaryJar", binaryJar)
        .set("sourceJar", sourceJar)
        .set("javadocUrl", javadocUrl);
  }

  public static Builder newPrebuiltJarRuleBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildRuleBuilder<PrebuiltJarRule> {

    private String binaryJar;
    private Optional<String> sourceJar = Optional.absent();
    private Optional<String> javadocUrl = Optional.absent();

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public PrebuiltJarRule build(BuildRuleResolver ruleResolver) {
      return new PrebuiltJarRule(createBuildRuleParams(ruleResolver),
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
    public Builder addDep(BuildTarget dep) {
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
