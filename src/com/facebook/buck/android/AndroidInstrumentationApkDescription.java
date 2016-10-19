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

package com.facebook.buck.android;

import static com.facebook.buck.jvm.java.JavaLibraryClasspathProvider.getClasspathDeps;

import com.facebook.buck.android.AndroidBinary.ExopackageMode;
import com.facebook.buck.android.AndroidBinary.PackageType;
import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.nio.file.Path;
import java.util.EnumSet;

public class AndroidInstrumentationApkDescription
    implements Description<AndroidInstrumentationApkDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("android_instrumentation_apk");

  private final ProGuardConfig proGuardConfig;
  private final JavacOptions javacOptions;
  private final ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> nativePlatforms;
  private final ListeningExecutorService dxExecutorService;
  private final CxxBuckConfig cxxBuckConfig;

  public AndroidInstrumentationApkDescription(
      ProGuardConfig proGuardConfig,
      JavacOptions androidJavacOptions,
      ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> nativePlatforms,
      ListeningExecutorService dxExecutorService,
      CxxBuckConfig cxxBuckConfig) {
    this.proGuardConfig = proGuardConfig;
    this.javacOptions = androidJavacOptions;
    this.nativePlatforms = nativePlatforms;
    this.dxExecutorService = dxExecutorService;
    this.cxxBuckConfig = cxxBuckConfig;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    BuildRule installableApk = resolver.getRule(args.apk);
    if (!(installableApk instanceof InstallableApk)) {
      throw new HumanReadableException(
          "In %s, apk='%s' must be an android_binary() or apk_genrule() but was %s().",
          params.getBuildTarget(),
          installableApk.getFullyQualifiedName(),
          installableApk.getType());
    }
    AndroidBinary apkUnderTest = getUnderlyingApk((InstallableApk) installableApk);

    ImmutableSortedSet<JavaLibrary> rulesToExcludeFromDex = FluentIterable.from(
        ImmutableSet.<JavaLibrary>builder()
            .addAll(apkUnderTest.getRulesToExcludeFromDex())
            .addAll(getClasspathDeps(apkUnderTest.getClasspathDeps()))
            .build())
        .toSortedSet(HasBuildTarget.BUILD_TARGET_COMPARATOR);

    // TODO(natthu): Instrumentation APKs should also exclude native libraries and assets from the
    // apk under test.
    AndroidPackageableCollection.ResourceDetails resourceDetails =
        apkUnderTest.getAndroidPackageableCollection().getResourceDetails();
    ImmutableSet<BuildTarget> resourcesToExclude = ImmutableSet.copyOf(
        Iterables.concat(
            resourceDetails.getResourcesWithNonEmptyResDir(),
            resourceDetails.getResourcesWithEmptyResButNonEmptyAssetsDir()));

    Path primaryDexPath =
        AndroidBinary.getPrimaryDexPath(params.getBuildTarget(), params.getProjectFilesystem());
    AndroidBinaryGraphEnhancer graphEnhancer = new AndroidBinaryGraphEnhancer(
        params,
        resolver,
        ResourceCompressionMode.DISABLED,
        FilterResourcesStep.ResourceFilter.EMPTY_FILTER,
        /* bannedDuplicateResourceTypes */ EnumSet.noneOf(RType.class),
        /* resourceUnionPackage */ Optional.absent(),
        /* locales */ ImmutableSet.of(),
        args.manifest,
        PackageType.INSTRUMENTED,
        apkUnderTest.getCpuFilters(),
        /* shouldBuildStringSourceMap */ false,
        /* shouldPreDex */ false,
        primaryDexPath,
        DexSplitMode.NO_SPLIT,
        FluentIterable.from(rulesToExcludeFromDex)
            .transform(HasBuildTarget::getBuildTarget)
            .toSet(),
        resourcesToExclude,
        /* skipCrunchPngs */ false,
        args.includesVectorDrawables.or(false),
        javacOptions,
        EnumSet.noneOf(ExopackageMode.class),
        apkUnderTest.getKeystore(),
        /* buildConfigValues */ BuildConfigFields.empty(),
        /* buildConfigValuesFile */ Optional.absent(),
        /* xzCompressionLevel */ Optional.absent(),
        /* trimResourceIds */ false,
        /* keepResourcePattern */ Optional.absent(),
        nativePlatforms,
        /* nativeLibraryMergeMap */ Optional.absent(),
        /* nativeLibraryMergeGlue */ Optional.absent(),
        /* nativeLibraryMergeCodeGenerator */ Optional.absent(),
        AndroidBinary.RelinkerMode.DISABLED,
        dxExecutorService,
        apkUnderTest.getManifestEntries(),
        cxxBuckConfig,
        new APKModuleGraph(
            targetGraph,
            params.getBuildTarget(),
            Optional.absent()));

    AndroidGraphEnhancementResult enhancementResult =
        graphEnhancer.createAdditionalBuildables();

    return new AndroidInstrumentationApk(
        params
            .copyWithExtraDeps(Suppliers.ofInstance(enhancementResult.getFinalDeps()))
            .appendExtraDeps(rulesToExcludeFromDex),
        new SourcePathResolver(resolver),
        proGuardConfig.getProguardJarOverride(),
        proGuardConfig.getProguardMaxHeapSize(),
        proGuardConfig.getProguardAgentPath(),
        apkUnderTest,
        rulesToExcludeFromDex,
        enhancementResult,
        dxExecutorService);

  }

  private static AndroidBinary getUnderlyingApk(InstallableApk installable) {
    if (installable instanceof AndroidBinary) {
      return (AndroidBinary) installable;
    } else if (installable instanceof ApkGenrule) {
      return getUnderlyingApk(((ApkGenrule) installable).getInstallableApk());
    } else {
      throw new IllegalStateException(
          installable.getBuildTarget().getFullyQualifiedName() +
              " must be backed by either an android_binary() or an apk_genrule()");
    }
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public SourcePath manifest;
    public BuildTarget apk;
    public Optional<ImmutableSortedSet<BuildTarget>> deps = Optional.of(ImmutableSortedSet.of());
    public Optional<Boolean> includesVectorDrawables = Optional.absent();
  }
}
