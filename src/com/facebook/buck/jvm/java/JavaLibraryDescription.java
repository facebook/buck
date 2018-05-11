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

import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasProvidedDeps;
import com.facebook.buck.core.description.arg.HasSrcs;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.core.HasSources;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.maven.aether.AetherUtil;
import com.facebook.buck.model.ImmutableBuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import org.immutables.value.Value;

public class JavaLibraryDescription
    implements Description<JavaLibraryDescriptionArg>,
        Flavored,
        VersionPropagator<JavaLibraryDescriptionArg> {

  private static final ImmutableSet<Flavor> SUPPORTED_FLAVORS =
      ImmutableSet.of(
          Javadoc.DOC_JAR,
          JavaLibrary.SRC_JAR,
          JavaLibrary.MAVEN_JAR,
          HasJavaAbi.CLASS_ABI_FLAVOR,
          HasJavaAbi.SOURCE_ABI_FLAVOR,
          HasJavaAbi.SOURCE_ONLY_ABI_FLAVOR,
          HasJavaAbi.VERIFIED_SOURCE_ABI_FLAVOR);

  private final ToolchainProvider toolchainProvider;
  private final JavaBuckConfig javaBuckConfig;

  public JavaLibraryDescription(
      ToolchainProvider toolchainProvider, JavaBuckConfig javaBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.javaBuckConfig = javaBuckConfig;
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
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      JavaLibraryDescriptionArg args) {
    BuildRuleResolver resolver = context.getBuildRuleResolver();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    // We know that the flavour we're being asked to create is valid, since the check is done when
    // creating the action graph from the target graph.

    ImmutableSortedSet<Flavor> flavors = buildTarget.getFlavors();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    if (flavors.contains(Javadoc.DOC_JAR)) {
      BuildTarget unflavored = ImmutableBuildTarget.of(buildTarget.getUnflavoredBuildTarget());
      BuildRule baseLibrary = resolver.requireRule(unflavored);

      JarShape shape =
          buildTarget.getFlavors().contains(JavaLibrary.MAVEN_JAR)
              ? JarShape.MAVEN
              : JarShape.SINGLE;

      JarShape.Summary summary = shape.gatherDeps(baseLibrary);
      ImmutableSet<SourcePath> sources =
          summary
              .getPackagedRules()
              .stream()
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
          summary
              .getClasspath()
              .stream()
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
            toolchainProvider
                .getByName(JavacOptionsProvider.DEFAULT_NAME, JavacOptionsProvider.class)
                .getJavacOptions(),
            buildTarget,
            projectFilesystem,
            resolver,
            args);

    DefaultJavaLibraryRules defaultJavaLibraryRules =
        DefaultJavaLibrary.rulesBuilder(
                buildTarget,
                projectFilesystem,
                context.getToolchainProvider(),
                params,
                resolver,
                context.getCellPathResolver(),
                new JavaConfiguredCompilerFactory(javaBuckConfig),
                javaBuckConfig,
                args)
            .setJavacOptions(javacOptions)
            .setToolchainProvider(context.getToolchainProvider())
            .build();

    if (HasJavaAbi.isAbiTarget(buildTarget)) {
      return defaultJavaLibraryRules.buildAbi();
    }

    DefaultJavaLibrary defaultJavaLibrary = defaultJavaLibraryRules.buildLibrary();

    if (!flavors.contains(JavaLibrary.MAVEN_JAR)) {
      return defaultJavaLibrary;
    } else {
      resolver.addToIndex(defaultJavaLibrary);
      return MavenUberJar.create(
          defaultJavaLibrary,
          buildTargetWithMavenFlavor,
          projectFilesystem,
          params,
          args.getMavenCoords(),
          args.getMavenPomTemplate());
    }
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
