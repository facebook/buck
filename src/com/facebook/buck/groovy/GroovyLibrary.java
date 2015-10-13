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

package com.facebook.buck.groovy;

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.classpaths.JavaLibraryClasspathProvider;
import com.facebook.buck.java.AnnotationProcessingParams;
import com.facebook.buck.java.CopyResourcesStep;
import com.facebook.buck.java.JarDirectoryStep;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.JavaLibraryRules;
import com.facebook.buck.java.JavaPackageFinder;
import com.facebook.buck.java.ResourcesRootPackageFinder;
import com.facebook.buck.jvmlang.JVMLangLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

public class GroovyLibrary extends JVMLangLibrary {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(LIBRARY);

  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> srcs;
  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> resources;
  @AddToRuleKey(stringify = true)
  private final Optional<Path> resourcesRoot;
  @AddToRuleKey
  private final ImmutableSortedSet<BuildRule> exportedDeps;
  @AddToRuleKey
  private final ImmutableSortedSet<BuildRule> providedDeps;

  private final Supplier<ImmutableSetMultimap<JavaLibrary, Path>> outputClasspathEntriesSupplier;
  private final Supplier<ImmutableSetMultimap<JavaLibrary, Path>> declaredClasspathEntriesSupplier;

  private final SourcePath abiJar;

  protected GroovyLibrary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Set<? extends SourcePath> srcs,
      Set<? extends SourcePath> resources,
      ImmutableSortedSet<BuildRule> exportedDeps,
      ImmutableSortedSet<BuildRule> providedDeps,
      SourcePath abiJar,
      Optional<Path> resourcesRoot) {

    super(
        params,
        resolver,
        exportedDeps, srcs, resources);
    this.srcs = ImmutableSortedSet.copyOf(srcs);
    this.resources = ImmutableSortedSet.copyOf(resources);
    this.exportedDeps = exportedDeps;
    this.providedDeps = providedDeps;
    this.resourcesRoot = resourcesRoot;
    this.abiJar = abiJar;

    this.outputClasspathEntriesSupplier =
        Suppliers.memoize(
            new Supplier<ImmutableSetMultimap<JavaLibrary, Path>>() {
              @Override
              public ImmutableSetMultimap<JavaLibrary, Path> get() {
                return JavaLibraryClasspathProvider.getOutputClasspathEntries(
                    GroovyLibrary.this,
                    getOutputJar());
              }
            });

    this.declaredClasspathEntriesSupplier =
        Suppliers.memoize(
            new Supplier<ImmutableSetMultimap<JavaLibrary, Path>>() {
              @Override
              public ImmutableSetMultimap<JavaLibrary, Path> get() {
                return JavaLibraryClasspathProvider.getDeclaredClasspathEntries(
                    GroovyLibrary.this);
              }
            });

  }

  /**
   * Building a java_library() rule entails compiling the .java files specified in the srcs
   * attribute. They are compiled into a directory under
   * t   * {@link com.facebook.buck.util.BuckConstant#SCRATCH_DIR}.
   */
  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    ImmutableSetMultimap<JavaLibrary, Path> declaredClasspathEntries =
        ImmutableSetMultimap.<JavaLibrary, Path>builder()
            .putAll(getDeclaredClasspathEntries())
            .build();

    // Always create the output directory, even if there are no .groovy files to compile because there
    // might be resources that need to be copied there.
    Path outputDirectory = getClassesDir(getBuildTarget());
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), outputDirectory));

    // We don't want to add these to the declared or transitive deps, since they're only used at
    // compile time.
    Collection<Path> provided = JavaLibraryClasspathProvider.getJavaLibraryDeps(providedDeps)
        .transformAndConcat(
            new Function<JavaLibrary, Collection<Path>>() {
              @Override
              public Collection<Path> apply(JavaLibrary input) {
                return input.getOutputClasspathEntries().values();
              }
            })
        .filter(Predicates.notNull())
        .toSet();

    ImmutableSet<Path> declared = ImmutableSet.<Path>builder()
        .addAll(declaredClasspathEntries.values())
        .addAll(provided)
        .build();

    // This adds the javac command, along with any supporting commands.
    createCommandsForCompilation(
        outputDirectory,
        declared,
        steps,
        getBuildTarget());

    // If there are resources, then link them to the appropriate place in the classes directory.
    JavaPackageFinder finder = context.getJavaPackageFinder();
    if (resourcesRoot.isPresent()) {
      finder = new ResourcesRootPackageFinder(resourcesRoot.get(), finder);
    }
    steps.add(
        new CopyResourcesStep(
            getProjectFilesystem(),
            getResolver(),
            getBuildTarget(),
            resources,
            outputDirectory,
            finder));

    if (getOutputJar().isPresent()) {
      steps.add(
          new MakeCleanDirectoryStep(
              getProjectFilesystem(),
              getOutputJarDirPath(getBuildTarget())));
      steps.add(
          new JarDirectoryStep(
              getProjectFilesystem(),
              getOutputJar().get(),
              Collections.singleton(outputDirectory),
          /* mainClass */ null,
          /* manifestFile */ null));
      buildableContext.recordArtifact(getOutputJar().get());
    }

    JavaLibraryRules.addAccumulateClassNamesStep(this, buildableContext, steps);

    return steps.build();
  }

  /**
   * @param outputDirectory          Directory to write class files to
   * @param declaredClasspathEntries Classpaths of all declared dependencies.
   * @param commands                 List of steps to add to.
   * @return a {@link Supplier} that will return the ABI for this rule after javac is executed.
   */
  protected void createCommandsForCompilation(
      Path outputDirectory,
      ImmutableSet<Path> declaredClasspathEntries,
      ImmutableList.Builder<Step> commands,
      BuildTarget target) {

    // Only run groovyc if there are .groovy files to compile.
    if (!getGroovySrcs().isEmpty()) {
      Path pathToSrcsList = BuildTargets.getGenPath(getBuildTarget(), "__%s__srcs");
      commands.add(new MkdirStep(getProjectFilesystem(), pathToSrcsList.getParent()));

      Optional<Path> workingDirectory;
      Path scratchDir = BuildTargets.getGenPath(target, "lib__%s____working_directory");
      commands.add(new MakeCleanDirectoryStep(getProjectFilesystem(), scratchDir));
      workingDirectory = Optional.of(scratchDir);

      final GroovycStep groovycStep = new GroovycStep(
          outputDirectory,
          workingDirectory,
          getGroovySrcs(),
          Optional.of(pathToSrcsList),
          declaredClasspathEntries,
          target,
          getProjectFilesystem());
      commands.add(groovycStep);
    }
  }

  @Override
  public Sha1HashCode getAbiKey() {
    return Sha1HashCode.fromHashCode(getRuleKey().getHashCode());
  }

  @Override
  public Optional<SourcePath> getAbiJar() {
    return getOutputJar().isPresent() ? Optional.of(abiJar) : Optional.<SourcePath>absent();
  }

  /**
   * @return directory path relative to the project root where .class files will be generated.
   * The return value does not end with a slash.
   */
  private static Path getClassesDir(BuildTarget target) {
    return BuildTargets.getScratchPath(target, "lib__%s__classes");
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getDeclaredClasspathEntries() {
    return declaredClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getOutputClasspathEntries() {
    return outputClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSortedSet<Path> getJavaSrcs() {
    return ImmutableSortedSet.copyOf(getResolver().getAllPaths(srcs));
  }

  @Override
  public ImmutableSortedSet<SourcePath> getSources() {
    throw new UnsupportedOperationException();
  }

  private ImmutableSortedSet<Path> getGroovySrcs() {
    return getJavaSrcs();
  }

  @Override
  public AnnotationProcessingParams getAnnotationProcessingParams() {
    return AnnotationProcessingParams.EMPTY;
  }

  @Override
  public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
    throw new UnsupportedOperationException("getClassNamesToHashes not supported in groovy");
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  @Override
  public Optional<String> getMavenCoords() {
    throw new UnsupportedOperationException();
  }

}
