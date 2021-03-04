/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.HasDepsQuery;
import com.facebook.buck.core.description.arg.HasProvidedDepsQuery;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.infer.InferConfig;
import com.facebook.buck.infer.InferJava;
import com.facebook.buck.infer.UnresolvedInferPlatform;
import com.facebook.buck.infer.toolchain.InferToolchain;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.ConfiguredCompilerFactory;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaCDBuckConfig;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaSourceJar;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.jvm.java.Nullsafe;
import com.facebook.buck.jvm.java.nullsafe.NullsafeConfig;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.util.MoreFunctions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import java.util.function.Function;
import org.immutables.value.Value;

public class AndroidLibraryDescription
    implements DescriptionWithTargetGraph<AndroidLibraryDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<
            AndroidLibraryDescription.AbstractAndroidLibraryDescriptionArg> {

  private static final Flavor DUMMY_R_DOT_JAVA_FLAVOR =
      AndroidLibraryGraphEnhancer.DUMMY_R_DOT_JAVA_FLAVOR;

  public enum JvmLanguage {
    JAVA,
    KOTLIN,
    SCALA,
  }

  private final JavaBuckConfig javaBuckConfig;
  private final JavaCDBuckConfig javaCDBuckConfig;
  private final DownwardApiConfig downwardApiConfig;
  private final AndroidLibraryCompilerFactory compilerFactory;
  private final JavacFactory javacFactory;
  private final Function<TargetConfiguration, Optional<UnresolvedInferPlatform>>
      unresolvedInferPlatform;

  public AndroidLibraryDescription(
      JavaBuckConfig javaBuckConfig,
      JavaCDBuckConfig javaCDBuckConfig,
      DownwardApiConfig downwardApiConfig,
      AndroidLibraryCompilerFactory compilerFactory,
      ToolchainProvider toolchainProvider) {
    this.javaBuckConfig = javaBuckConfig;
    this.javaCDBuckConfig = javaCDBuckConfig;
    this.downwardApiConfig = downwardApiConfig;
    this.compilerFactory = compilerFactory;
    this.javacFactory = JavacFactory.getDefault(toolchainProvider);
    this.unresolvedInferPlatform =
        MoreFunctions.memoize(
            toolchainTargetConfiguration ->
                toolchainProvider
                    .getByNameIfPresent(
                        InferToolchain.DEFAULT_NAME,
                        toolchainTargetConfiguration,
                        InferToolchain.class)
                    .map(InferToolchain::getDefaultPlatform));
  }

  @Override
  public Class<AndroidLibraryDescriptionArg> getConstructorArgType() {
    return AndroidLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AndroidLibraryDescriptionArg args) {
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    if (buildTarget.getFlavors().contains(JavaLibrary.SRC_JAR)) {
      return new JavaSourceJar(
          buildTarget, projectFilesystem, params, args.getSrcs(), args.getMavenCoords());
    }

    if (args.isSkipNonUnionRDotJava()) {
      Preconditions.checkArgument(
          args.getResourceUnionPackage().isPresent(),
          "union_package should be specified if skip_non_union_r_dot_java is set");
    }

    ToolchainProvider toolchainProvider = context.getToolchainProvider();
    boolean hasDummyRDotJavaFlavor = buildTarget.getFlavors().contains(DUMMY_R_DOT_JAVA_FLAVOR);
    JavacOptions javacOptions =
        JavacOptionsFactory.create(
            toolchainProvider
                .getByName(
                    JavacOptionsProvider.DEFAULT_NAME,
                    buildTarget.getTargetConfiguration(),
                    JavacOptionsProvider.class)
                .getJavacOptions(),
            buildTarget,
            context.getActionGraphBuilder(),
            projectFilesystem.getRootPath(),
            args);
    ConfiguredCompilerFactory compilerFactory =
        this.compilerFactory.getCompiler(
            args.getLanguage().orElse(JvmLanguage.JAVA),
            javacFactory,
            buildTarget.getTargetConfiguration());

    FlavorSet flavors = buildTarget.getFlavors();

    Optional<Flavor> inferFlavor = InferJava.findSupportedFlavor(flavors);
    if (inferFlavor.isPresent()) {
      return InferJava.create(
          inferFlavor.get(),
          buildTarget,
          projectFilesystem,
          context.getActionGraphBuilder(),
          javacOptions,
          compilerFactory.getExtraClasspathProvider(
              toolchainProvider, buildTarget.getTargetConfiguration()),
          unresolvedInferPlatform
              .apply(buildTarget.getTargetConfiguration())
              .orElseThrow(
                  () ->
                      new HumanReadableException(
                          "Cannot use #nullsafe flavor: infer platform not configured")),
          InferConfig.of(javaBuckConfig.getDelegate()),
          downwardApiConfig);
    }

    // Even with Nullsafe plugin enabled we still want to use original javac options for RDot
    JavacOptions rDotJavacOptions = javacOptions;
    if (Nullsafe.hasSupportedFlavor(flavors)) {
      javacOptions =
          Nullsafe.augmentJavacOptions(
              javacOptions,
              buildTarget,
              context.getActionGraphBuilder(),
              projectFilesystem,
              NullsafeConfig.of(javaBuckConfig.getDelegate()));
    }

    AndroidLibrary.Builder androidLibraryBuilder =
        AndroidLibrary.builder(
            buildTarget,
            projectFilesystem,
            toolchainProvider,
            params,
            context.getActionGraphBuilder(),
            javaBuckConfig,
            javaCDBuckConfig,
            downwardApiConfig,
            javacFactory,
            javacOptions,
            args,
            compilerFactory,
            context.getCellPathResolver(),
            rDotJavacOptions);

    if (Nullsafe.hasSupportedFlavor(flavors)) {
      return Nullsafe.create(
          context.getActionGraphBuilder(), androidLibraryBuilder.buildLibraryForNullsafe());
    }

    if (hasDummyRDotJavaFlavor) {
      return androidLibraryBuilder.buildDummyRDotJava();
    } else if (JavaAbis.isAbiTarget(buildTarget)) {
      return androidLibraryBuilder.buildAbi();
    }
    return androidLibraryBuilder.build();
  }

  @Override
  public boolean hasFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    return flavors.isEmpty()
        || flavors.equals(ImmutableSet.of(JavaLibrary.SRC_JAR))
        || flavors.equals(ImmutableSet.of(DUMMY_R_DOT_JAVA_FLAVOR))
        || flavors.equals(ImmutableSet.of(JavaAbis.CLASS_ABI_FLAVOR))
        || flavors.equals(ImmutableSet.of(JavaAbis.SOURCE_ABI_FLAVOR))
        || flavors.equals(ImmutableSet.of(JavaAbis.SOURCE_ONLY_ABI_FLAVOR))
        || flavors.equals(ImmutableSet.of(JavaAbis.VERIFIED_SOURCE_ABI_FLAVOR))
        || flavors.equals(ImmutableSet.of(InferJava.INFER_NULLSAFE))
        || flavors.equals(ImmutableSet.of(InferJava.INFER_JAVA_CAPTURE))
        || Nullsafe.hasSupportedFlavor(flavors);
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractAndroidLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    compilerFactory
        .getCompiler(
            constructorArg.getLanguage().orElse(JvmLanguage.JAVA),
            javacFactory,
            buildTarget.getTargetConfiguration())
        .addTargetDeps(
            buildTarget.getTargetConfiguration(), extraDepsBuilder, targetGraphOnlyDepsBuilder);
    javacFactory.addParseTimeDeps(
        targetGraphOnlyDepsBuilder, constructorArg, buildTarget.getTargetConfiguration());

    unresolvedInferPlatform
        .apply(buildTarget.getTargetConfiguration())
        .ifPresent(
            p ->
                UnresolvedInferPlatform.addParseTimeDepsToInferFlavored(
                    targetGraphOnlyDepsBuilder, buildTarget, p));
    Nullsafe.addParseTimeDeps(
        targetGraphOnlyDepsBuilder, buildTarget, NullsafeConfig.of(javaBuckConfig.getDelegate()));
  }

  public interface CoreArg
      extends JavaLibraryDescription.CoreArg,
          AndroidKotlinCoreArg,
          HasDepsQuery,
          HasProvidedDepsQuery {
    Optional<SourcePath> getManifest();

    Optional<String> getResourceUnionPackage();

    @Value.Default
    default boolean isSkipNonUnionRDotJava() {
      return false;
    }

    Optional<String> getFinalRName();
  }

  @RuleArg
  interface AbstractAndroidLibraryDescriptionArg extends CoreArg {

    @Override
    default AndroidLibraryDescriptionArg withDepsQuery(Query query) {
      if (getDepsQuery().equals(Optional.of(query))) {
        return (AndroidLibraryDescriptionArg) this;
      }
      return AndroidLibraryDescriptionArg.builder().from(this).setDepsQuery(query).build();
    }

    @Override
    default AndroidLibraryDescriptionArg withProvidedDepsQuery(Query query) {
      if (getProvidedDepsQuery().equals(Optional.of(query))) {
        return (AndroidLibraryDescriptionArg) this;
      }
      return AndroidLibraryDescriptionArg.builder().from(this).setProvidedDepsQuery(query).build();
    }
  }
}
