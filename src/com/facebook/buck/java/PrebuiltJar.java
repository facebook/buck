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
import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.AnnotationProcessingData;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableParams;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.ExportDependencies;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
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
import java.util.Collections;
import java.util.List;

public class PrebuiltJar extends AbstractBuildable
    implements JavaLibrary, HasClasspathEntries, ExportDependencies,
    InitializableFromDisk<JavaLibrary.Data> {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(LIBRARY);

  private final Path binaryJar;
  private final Optional<Path> sourceJar;
  private final Optional<SourcePath> gwtJar;
  private final Optional<String> javadocUrl;
  private final Supplier<ImmutableSetMultimap<JavaLibrary, Path>>
      transitiveClasspathEntriesSupplier;

  private final Supplier<ImmutableSetMultimap<JavaLibrary, Path>>
      declaredClasspathEntriesSupplier;

  private final BuildOutputInitializer<Data> buildOutputInitializer;
  private final BuildTarget target;
  private final ImmutableSortedSet<BuildRule> deps;

  PrebuiltJar(
      BuildableParams buildableParams,
      Path classesJar,
      Optional<Path> sourceJar,
      Optional<SourcePath> gwtJar,
      Optional<String> javadocUrl) {
    this.target = buildableParams.getBuildTarget();
    this.deps = buildableParams.getDeps();
    this.binaryJar = Preconditions.checkNotNull(classesJar);
    this.sourceJar = Preconditions.checkNotNull(sourceJar);
    this.gwtJar = Preconditions.checkNotNull(gwtJar);
    this.javadocUrl = Preconditions.checkNotNull(javadocUrl);

    transitiveClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<JavaLibrary, Path>>() {
          @Override
          public ImmutableSetMultimap<JavaLibrary, Path> get() {
            ImmutableSetMultimap.Builder<JavaLibrary, Path> classpathEntries =
                ImmutableSetMultimap.builder();
            classpathEntries.put(PrebuiltJar.this, getBinaryJar());
            classpathEntries.putAll(Classpaths.getClasspathEntries(deps));
            return classpathEntries.build();
          }
        });

    declaredClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<JavaLibrary, Path>>() {
          @Override
          public ImmutableSetMultimap<JavaLibrary, Path> get() {
            ImmutableSetMultimap.Builder<JavaLibrary, Path> classpathEntries =
                ImmutableSetMultimap.builder();
            classpathEntries.put(PrebuiltJar.this, getBinaryJar());
            return classpathEntries.build();
          }
        });

    buildOutputInitializer =
        new BuildOutputInitializer<>(buildableParams.getBuildTarget(), this);
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
  public BuildTarget getBuildTarget() {
    return target;
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    ImmutableList.Builder<Path> builder = ImmutableList.builder();
    builder.add(binaryJar);
    Optionals.addIfPresent(sourceJar, builder);
    if (gwtJar.isPresent()) {
      SourcePath gwtJarSourcePath = gwtJar.get();
      Collection<Path> safePaths = SourcePaths.filterInputsToCompareToOutput(
          Collections.singleton(gwtJarSourcePath));
      builder.addAll(safePaths);
    }
    return builder.build();
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
  public ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries() {
    return transitiveClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getDeclaredClasspathEntries() {
    return declaredClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getOutputClasspathEntries() {
    return ImmutableSetMultimap.<JavaLibrary, Path>of(this, getBinaryJar());
  }

  @Override
  public ImmutableSortedSet<SourcePath> getJavaSrcs() {
    return ImmutableSortedSet.of();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getExportedDeps() {
    return deps;
  }

  @Override
  public AnnotationProcessingData getAnnotationProcessingData() {
    return AnnotationProcessingData.EMPTY;
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, final BuildableContext buildableContext) {
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
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .setReflectively("sourceJar", sourceJar)
        .setReflectively("gwtJar", gwtJar)
        .set("javadocUrl", javadocUrl);
  }
}
