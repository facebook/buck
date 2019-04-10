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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasProvidedDeps;
import com.facebook.buck.core.description.arg.HasSrcs;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.jvm.core.HasSources;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.maven.aether.AetherUtil;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableCollection.Builder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import org.immutables.value.Value;

public class JavaLibraryDescription
    implements DescriptionWithTargetGraph<JavaLibraryDescriptionArg>,
        Flavored,
        VersionPropagator<JavaLibraryDescriptionArg>,
        ImplicitDepsInferringDescription<JavaLibraryDescriptionArg> {

  private static final ImmutableSet<Flavor> SUPPORTED_FLAVORS =
      ImmutableSet.of(
          Javadoc.DOC_JAR,
          JavaLibrary.SRC_JAR,
          JavaLibrary.MAVEN_JAR,
          JavaAbis.CLASS_ABI_FLAVOR,
          JavaAbis.SOURCE_ABI_FLAVOR,
          JavaAbis.SOURCE_ONLY_ABI_FLAVOR,
          JavaAbis.VERIFIED_SOURCE_ABI_FLAVOR);

  private final JavaBuckConfig javaBuckConfig;
  private final JavacFactory javacFactory;

  public JavaLibraryDescription(
      ToolchainProvider toolchainProvider, JavaBuckConfig javaBuckConfig) {
    this.javaBuckConfig = javaBuckConfig;
    this.javacFactory = JavacFactory.getDefault(toolchainProvider);
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return SUPPORTED_FLAVORS.containsAll(flavors);
  }

  @Override
  public Class<JavaLibraryDescriptionArg> getConstructorArgType() {
    return JavaLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      JavaLibraryDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    // We know that the flavour we're being asked to create is valid, since the check is done when
    // creating the action graph from the target graph.

    ImmutableSortedSet<Flavor> flavors = buildTarget.getFlavors();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    if (flavors.contains(Javadoc.DOC_JAR)) {
      BuildTarget unflavored = buildTarget.withoutFlavors();
      BuildRule baseLibrary = graphBuilder.requireRule(unflavored);

      JarShape shape =
          buildTarget.getFlavors().contains(JavaLibrary.MAVEN_JAR)
              ? JarShape.MAVEN
              : JarShape.SINGLE;

      JarShape.Summary summary = shape.gatherDeps(baseLibrary);
      ImmutableSet<SourcePath> sources =
          summary.getPackagedRules().stream()
              .filter(HasSources.class::isInstance)
              .map(rule -> ((HasSources) rule).getSources())
              .flatMap(Collection::stream)
              .collect(ImmutableSet.toImmutableSet());

      // In theory, the only deps we need are the ones that contribute to the sourcepaths. However,
      // javadoc wants to have classes being documented have all their deps be available somewhere.
      // Ideally, we'd not build everything, but then we're not able to document any classes that
      // rely on auto-generated classes, such as those created by the Immutables library. Oh well.
      // Might as well add them as deps. *sigh*
      ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();
      // Sourcepath deps
      deps.addAll(ruleFinder.filterBuildRuleInputs(sources));
      // Classpath deps
      deps.add(baseLibrary);
      deps.addAll(
          summary.getClasspath().stream()
              .filter(rule -> HasClasspathEntries.class.isAssignableFrom(rule.getClass()))
              .flatMap(rule -> rule.getTransitiveClasspathDeps().stream())
              .iterator());
      BuildRuleParams emptyParams = params.withDeclaredDeps(deps.build()).withoutExtraDeps();

      return new Javadoc(
          buildTarget,
          projectFilesystem,
          emptyParams,
          args.getMavenCoords(),
          args.getMavenPomTemplate(),
          summary.getMavenDeps(),
          sources);
    }

    BuildTarget buildTargetWithMavenFlavor = buildTarget;
    if (flavors.contains(JavaLibrary.MAVEN_JAR)) {

      // Maven rules will depend upon their vanilla versions, so the latter have to be constructed
      // without the maven flavor to prevent output-path conflict
      buildTarget = buildTarget.withoutFlavors(JavaLibrary.MAVEN_JAR);
    }

    if (flavors.contains(JavaLibrary.SRC_JAR)) {
      Optional<String> mavenCoords =
          args.getMavenCoords()
              .map(input -> AetherUtil.addClassifier(input, AetherUtil.CLASSIFIER_SOURCES));

      if (!flavors.contains(JavaLibrary.MAVEN_JAR)) {
        return new JavaSourceJar(
            buildTarget, projectFilesystem, params, args.getSrcs(), mavenCoords);
      } else {
        return MavenUberJar.SourceJar.create(
            buildTargetWithMavenFlavor,
            projectFilesystem,
            params,
            args.getSrcs(),
            mavenCoords,
            args.getMavenPomTemplate());
      }
    }

    JavacOptions javacOptions =
        JavacOptionsFactory.create(
            context
                .getToolchainProvider()
                .getByName(JavacOptionsProvider.DEFAULT_NAME, JavacOptionsProvider.class)
                .getJavacOptions(),
            buildTarget,
            graphBuilder,
            args);

    DefaultJavaLibraryRules defaultJavaLibraryRules =
        DefaultJavaLibrary.rulesBuilder(
                buildTarget,
                projectFilesystem,
                context.getToolchainProvider(),
                params,
                graphBuilder,
                context.getCellPathResolver(),
                new JavaConfiguredCompilerFactory(javaBuckConfig, javacFactory),
                javaBuckConfig,
                args)
            .setJavacOptions(javacOptions)
            .setToolchainProvider(context.getToolchainProvider())
            .build();

    if (JavaAbis.isAbiTarget(buildTarget)) {
      return defaultJavaLibraryRules.buildAbi();
    }

    DefaultJavaLibrary defaultJavaLibrary = defaultJavaLibraryRules.buildLibrary();

    if (!flavors.contains(JavaLibrary.MAVEN_JAR)) {
      return defaultJavaLibrary;
    } else {
      graphBuilder.addToIndex(defaultJavaLibrary);
      return MavenUberJar.create(
          defaultJavaLibrary,
          buildTargetWithMavenFlavor,
          projectFilesystem,
          params,
          args.getMavenCoords(),
          args.getMavenPomTemplate());
    }
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      JavaLibraryDescriptionArg constructorArg,
      Builder<BuildTarget> extraDepsBuilder,
      Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    javacFactory.addParseTimeDeps(targetGraphOnlyDepsBuilder, constructorArg);
  }

  public interface CoreArg
      extends JvmLibraryArg, HasDeclaredDeps, HasProvidedDeps, HasSrcs, HasTests {
    @Value.NaturalOrder
    ImmutableSortedSet<SourcePath> getResources();

    Optional<SourcePath> getProguardConfig();

    ImmutableList<String> getPostprocessClassesCommands();

    @Hint(isInput = false)
    Optional<Path> getResourcesRoot();

    Optional<SourcePath> getUnbundledResourcesRoot();

    Optional<SourcePath> getManifestFile();

    Optional<String> getMavenCoords();

    Optional<SourcePath> getMavenPomTemplate();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getExportedDeps();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getSourceOnlyAbiDeps();
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractJavaLibraryDescriptionArg extends CoreArg {}
}
