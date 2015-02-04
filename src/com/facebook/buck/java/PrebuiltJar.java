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

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.ExportDependencies;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.Optionals;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;

import java.nio.file.Path;

@BuildsAnnotationProcessor
public class PrebuiltJar extends AbstractBuildRule
    implements AndroidPackageable, ExportDependencies, HasClasspathEntries,
    InitializableFromDisk<JavaLibrary.Data>, JavaLibrary {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(LIBRARY);

  private final SourcePath binaryJar;
  private final Optional<SourcePath> sourceJar;
  private final Optional<SourcePath> gwtJar;
  private final Optional<String> javadocUrl;
  private final Path abiJar;
  private final Supplier<ImmutableSetMultimap<JavaLibrary, Path>>
      transitiveClasspathEntriesSupplier;

  private final Supplier<ImmutableSetMultimap<JavaLibrary, Path>>
      declaredClasspathEntriesSupplier;

  private final BuildOutputInitializer<Data> buildOutputInitializer;

  public PrebuiltJar(
      BuildRuleParams params,
      SourcePathResolver resolver,
      SourcePath binaryJar,
      Optional<SourcePath> sourceJar,
      Optional<SourcePath> gwtJar,
      Optional<String> javadocUrl) {
    super(params, resolver);
    this.binaryJar = binaryJar;
    this.sourceJar = sourceJar;
    this.gwtJar = gwtJar;
    this.javadocUrl = javadocUrl;

    this.abiJar = BuildTargets.getGenPath(getBuildTarget(), "%s-abi.jar");

    transitiveClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<JavaLibrary, Path>>() {
          @Override
          public ImmutableSetMultimap<JavaLibrary, Path> get() {
            ImmutableSetMultimap.Builder<JavaLibrary, Path> classpathEntries =
                ImmutableSetMultimap.builder();
            classpathEntries.put(PrebuiltJar.this, getResolver().getPath(getBinaryJar()));
            classpathEntries.putAll(Classpaths.getClasspathEntries(
                    PrebuiltJar.this.getDeclaredDeps()));
            return classpathEntries.build();
          }
        });

    declaredClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<JavaLibrary, Path>>() {
          @Override
          public ImmutableSetMultimap<JavaLibrary, Path> get() {
            ImmutableSetMultimap.Builder<JavaLibrary, Path> classpathEntries =
                ImmutableSetMultimap.builder();
            classpathEntries.put(PrebuiltJar.this, getResolver().getPath(getBinaryJar()));
            return classpathEntries.build();
          }
        });

    buildOutputInitializer =
        new BuildOutputInitializer<>(params.getBuildTarget(), this);
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  public SourcePath getBinaryJar() {
    return binaryJar;
  }

  public Optional<SourcePath> getSourceJar() {
    return sourceJar;
  }

  public Optional<String> getJavadocUrl() {
    return javadocUrl;
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    ImmutableList.Builder<SourcePath> inputsToCompareToOutput = ImmutableList.builder();
    inputsToCompareToOutput.add(binaryJar);
    Optionals.addIfPresent(sourceJar, inputsToCompareToOutput);
    Optionals.addIfPresent(gwtJar, inputsToCompareToOutput);
    return getResolver().filterInputsToCompareToOutput(inputsToCompareToOutput.build());
  }

  @Override
  public Sha1HashCode getAbiKey() {
    return buildOutputInitializer.getBuildOutput().getAbiKey();
  }

  @Override
  public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
    return buildOutputInitializer.getBuildOutput().getClassNamesToHashes();
  }

  @Override
  public JavaLibrary.Data initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    return JavaLibraryRules.initializeFromDisk(getBuildTarget(), onDiskBuildInfo);
  }

  @Override
  public BuildOutputInitializer<Data> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getDepsForTransitiveClasspathEntries() {
    return getDeps();
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries() {
    return transitiveClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getDeclaredClasspathEntries() {
    return declaredClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getOutputClasspathEntries() {
    return ImmutableSetMultimap.<JavaLibrary, Path>of(this, getResolver().getPath(getBinaryJar()));
  }

  @Override
  public ImmutableSortedSet<Path> getJavaSrcs() {
    return ImmutableSortedSet.of();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getExportedDeps() {
    return getDeclaredDeps();
  }

  @Override
  public AnnotationProcessingParams getAnnotationProcessingParams() {
    return AnnotationProcessingParams.EMPTY;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      final BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // Create a step to compute the ABI key.
    steps.add(new MkdirStep(abiJar.getParent()));
    steps.add(new RmStep(abiJar, true));
    steps.add(new CalculateAbiStep(buildableContext, getResolver().getPath(binaryJar), abiJar));

    JavaLibraryRules.addAccumulateClassNamesStep(this, buildableContext, steps);

    return steps.build();
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    return AndroidPackageableCollector.getPackageableRules(getDeclaredDeps());
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    collector.addClasspathEntry(this, getResolver().getPath(getBinaryJar()));
    collector.addPathToThirdPartyJar(getBuildTarget(), getResolver().getPath(getBinaryJar()));
  }

  @Override
  public Path getPathToOutputFile() {
    return getResolver().getPath(getBinaryJar());
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .setReflectively("binaryJar", binaryJar)
        .setReflectively("sourceJar", sourceJar)
        .setReflectively("gwtJar", gwtJar)
        .setReflectively("javadocUrl", javadocUrl);
  }

}
