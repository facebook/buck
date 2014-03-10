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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.DoNotUseAbstractBuildable;
import com.facebook.buck.rules.ExportDependencies;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.Optionals;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

public class PrebuiltJarRule extends DoNotUseAbstractBuildable
    implements JavaLibraryRule, HasClasspathEntries, ExportDependencies,
    InitializableFromDisk<JavaLibraryRule.Data> {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(LIBRARY);

  private final Path binaryJar;
  private final Optional<Path> sourceJar;
  private final Optional<String> javadocUrl;
  private final Supplier<ImmutableSetMultimap<JavaLibraryRule, String>>
      transitiveClasspathEntriesSupplier;

  private final Supplier<ImmutableSetMultimap<JavaLibraryRule, String>>
      declaredClasspathEntriesSupplier;

  @Nullable
  private JavaLibraryRule.Data buildOutput;

  PrebuiltJarRule(BuildRuleParams buildRuleParams,
      Path classesJar,
      Optional<Path> sourceJar,
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
            classpathEntries.put(PrebuiltJarRule.this, getBinaryJar().toString());
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
            classpathEntries.put(PrebuiltJarRule.this, getBinaryJar().toString());
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

  public Path getBinaryJar() {
    return binaryJar;
  }

  public Optional<Path> getSourceJar() {
    return sourceJar;
  }

  public Optional<String> getJavadocUrl() {
    return javadocUrl;
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    ImmutableList.Builder<Path> builder = ImmutableList.builder();
    builder.add(binaryJar);
    Optionals.addIfPresent(sourceJar, builder);
    return builder.build();
  }

  @Override
  public Sha1HashCode getAbiKey() {
    return getBuildOutput().getAbiKey();
  }

  @Override
  public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
    return getBuildOutput().getClassNamesToHashes();
  }

  @Override
  public JavaLibraryRule.Data initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    return JavaLibraryRules.initializeFromDisk(getBuildTarget(), onDiskBuildInfo);
  }

  @Override
  public void setBuildOutput(JavaLibraryRule.Data buildOutput) {
    Preconditions.checkState(this.buildOutput == null,
        "buildOutput should not already be set for %s.",
        this);
    this.buildOutput = buildOutput;
  }

  @Override
  public JavaLibraryRule.Data getBuildOutput() {
    Preconditions.checkState(buildOutput != null, "buildOutput must already be set for %s.", this);
    return buildOutput;
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
    return ImmutableSetMultimap.<JavaLibraryRule, String>builder()
        .put(this, getBinaryJar().toString()).build();
  }

  @Override
  public ImmutableSortedSet<Path> getJavaSrcs() {
    return ImmutableSortedSet.of();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getExportedDeps() {
    return getDeps();
  }

  @Override
  public AnnotationProcessingData getAnnotationProcessingData() {
    return AnnotationProcessingData.EMPTY;
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, final BuildableContext buildableContext)
      throws IOException {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // Create a step to compute the ABI key.
    steps.add(new CalculateAbiStep(buildableContext));

    JavaLibraryRules.addAccumulateClassNamesStep(this, buildableContext, steps);

    buildableContext.recordArtifact(getBinaryJar());
    return steps.build();
  }

  class CalculateAbiStep implements Step {

    private final BuildableContext buildableContext;

    private CalculateAbiStep(BuildableContext buildableContext) {
      this.buildableContext = Preconditions.checkNotNull(buildableContext);
    }

    @Override
    public int execute(ExecutionContext context) {
      File jarFile = context.getProjectFilesystem().getFileForRelativePath(binaryJar);
      HashCode fileSha1;
      try {
        fileSha1 = Files.hash(jarFile, Hashing.sha1());
      } catch (IOException e) {
        context.logError(e, "Failed to calculate ABI for %s.", binaryJar);
        return 1;
      }

      Sha1HashCode abiKey = new Sha1HashCode(fileSha1.toString());
      buildableContext.addMetadata(AbiRule.ABI_KEY_ON_DISK_METADATA, abiKey.getHash());

      return 0;
    }

    @Override
    public String getShortName() {
      return "calculate_abi";
    }

    @Override
    public String getDescription(ExecutionContext context) {
      return String.format("%s %s", getShortName(), binaryJar);
    }

  }

  @Override
  public Path getPathToOutputFile() {
    return getBinaryJar();
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) throws IOException {
    return super.appendToRuleKey(builder)
        .set("javadocUrl", javadocUrl);
  }

  public static Builder newPrebuiltJarRuleBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildRuleBuilder<PrebuiltJarRule> {

    private Path binaryJar;
    private Optional<Path> sourceJar = Optional.absent();
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

    public Builder setBinaryJar(Path binaryJar) {
      this.binaryJar = binaryJar;
      return this;
    }

    public Builder setSourceJar(Optional<Path> sourceJar) {
      this.sourceJar = Preconditions.checkNotNull(sourceJar);
      return this;
    }

    public Builder setJavadocUrl(Optional<String> javadocUrl) {
      this.javadocUrl = Preconditions.checkNotNull(javadocUrl);
      return this;
    }

  }
}
