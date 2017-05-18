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
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import org.immutables.value.Value;

public class AndroidInstrumentationApkDescription
    implements Description<AndroidInstrumentationApkDescriptionArg> {

  private final JavaBuckConfig javaBuckConfig;
  private final ProGuardConfig proGuardConfig;
  private final JavacOptions javacOptions;
  private final ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> nativePlatforms;
  private final ListeningExecutorService dxExecutorService;
  private final CxxBuckConfig cxxBuckConfig;
  private final DxConfig dxConfig;

  public AndroidInstrumentationApkDescription(
      JavaBuckConfig javaBuckConfig,
      ProGuardConfig proGuardConfig,
      JavacOptions androidJavacOptions,
      ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> nativePlatforms,
      ListeningExecutorService dxExecutorService,
      CxxBuckConfig cxxBuckConfig,
      DxConfig dxConfig) {
    this.javaBuckConfig = javaBuckConfig;
    this.proGuardConfig = proGuardConfig;
    this.javacOptions = androidJavacOptions;
    this.nativePlatforms = nativePlatforms;
    this.dxExecutorService = dxExecutorService;
    this.cxxBuckConfig = cxxBuckConfig;
    this.dxConfig = dxConfig;
  }

  @Override
  public Class<AndroidInstrumentationApkDescriptionArg> getConstructorArgType() {
    return AndroidInstrumentationApkDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AndroidInstrumentationApkDescriptionArg args)
      throws NoSuchBuildTargetException {
    BuildRule installableApk = resolver.getRule(args.getApk());
    if (!(installableApk instanceof HasInstallableApk)) {
      throw new HumanReadableException(
          "In %s, apk='%s' must be an android_binary() or apk_genrule() but was %s().",
          params.getBuildTarget(),
          installableApk.getFullyQualifiedName(),
          installableApk.getType());
    }
    AndroidBinary apkUnderTest = getUnderlyingApk((HasInstallableApk) installableApk);

    ImmutableSortedSet<JavaLibrary> rulesToExcludeFromDex =
        new ImmutableSortedSet.Builder<>(Ordering.<JavaLibrary>natural())
            .addAll(apkUnderTest.getRulesToExcludeFromDex())
            .addAll(getClasspathDeps(apkUnderTest.getClasspathDeps()))
            .build();

    // TODO(natthu): Instrumentation APKs should also exclude native libraries and assets from the
    // apk under test.
    AndroidPackageableCollection.ResourceDetails resourceDetails =
        apkUnderTest.getAndroidPackageableCollection().getResourceDetails();
    ImmutableSet<BuildTarget> resourcesToExclude =
        ImmutableSet.copyOf(
            Iterables.concat(
                resourceDetails.getResourcesWithNonEmptyResDir(),
                resourceDetails.getResourcesWithEmptyResButNonEmptyAssetsDir()));

    Path primaryDexPath =
        AndroidBinary.getPrimaryDexPath(params.getBuildTarget(), params.getProjectFilesystem());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    AndroidBinaryGraphEnhancer graphEnhancer =
        new AndroidBinaryGraphEnhancer(
            params,
            targetGraph,
            resolver,
            cellRoots,
            AndroidBinary.AaptMode.AAPT1,
            ResourceCompressionMode.DISABLED,
            FilterResourcesStep.ResourceFilter.EMPTY_FILTER,
            /* bannedDuplicateResourceTypes */ EnumSet.noneOf(RType.class),
            /* resourceUnionPackage */ Optional.empty(),
            /* locales */ ImmutableSet.of(),
            args.getManifest(),
            PackageType.INSTRUMENTED,
            apkUnderTest.getCpuFilters(),
            /* shouldBuildStringSourceMap */ false,
            /* shouldPreDex */ false,
            primaryDexPath,
            DexSplitMode.NO_SPLIT,
            rulesToExcludeFromDex
                .stream()
                .map(BuildRule::getBuildTarget)
                .collect(MoreCollectors.toImmutableSet()),
            resourcesToExclude,
            /* skipCrunchPngs */ false,
            args.getIncludesVectorDrawables(),
            javaBuckConfig,
            JavacFactory.create(ruleFinder, javaBuckConfig, null),
            javacOptions,
            EnumSet.noneOf(ExopackageMode.class),
            /* buildConfigValues */ BuildConfigFields.empty(),
            /* buildConfigValuesFile */ Optional.empty(),
            /* xzCompressionLevel */ Optional.empty(),
            /* trimResourceIds */ false,
            /* keepResourcePattern */ Optional.empty(),
            nativePlatforms,
            /* nativeLibraryMergeMap */ Optional.empty(),
            /* nativeLibraryMergeGlue */ Optional.empty(),
            /* nativeLibraryMergeCodeGenerator */ Optional.empty(),
            /* nativeLibraryProguardConfigGenerator */ Optional.empty(),
            Optional.empty(),
            AndroidBinary.RelinkerMode.DISABLED,
            dxExecutorService,
            apkUnderTest.getManifestEntries(),
            cxxBuckConfig,
            new APKModuleGraph(targetGraph, params.getBuildTarget(), Optional.empty()),
            dxConfig,
            /* postFilterResourcesCommands */ Optional.empty());

    AndroidGraphEnhancementResult enhancementResult = graphEnhancer.createAdditionalBuildables();

    return new AndroidInstrumentationApk(
        params
            .copyReplacingExtraDeps(Suppliers.ofInstance(enhancementResult.getFinalDeps()))
            .copyAppendingExtraDeps(rulesToExcludeFromDex),
        ruleFinder,
        proGuardConfig.getProguardJarOverride(),
        proGuardConfig.getProguardMaxHeapSize(),
        proGuardConfig.getProguardAgentPath(),
        apkUnderTest,
        rulesToExcludeFromDex,
        enhancementResult,
        dxExecutorService);
  }

  private static AndroidBinary getUnderlyingApk(HasInstallableApk installable) {
    if (installable instanceof AndroidBinary) {
      return (AndroidBinary) installable;
    } else if (installable instanceof ApkGenrule) {
      return getUnderlyingApk(((ApkGenrule) installable).getInstallableApk());
    } else {
      throw new IllegalStateException(
          installable.getBuildTarget().getFullyQualifiedName()
              + " must be backed by either an android_binary() or an apk_genrule()");
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractAndroidInstrumentationApkDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps {
    SourcePath getManifest();

    BuildTarget getApk();

    @Value.Default
    default boolean getIncludesVectorDrawables() {
      return false;
    }
  }
}
