/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.android.FilterResourcesSteps.ResourceFilter;
import com.facebook.buck.android.exopackage.ExopackageMode;
import com.facebook.buck.android.redex.RedexOptions;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.Keystore;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.google.common.collect.ImmutableSortedSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class AndroidBinaryFactory {

  private static final Flavor ANDROID_MODULARITY_VERIFICATION_FLAVOR =
      InternalFlavor.of("modularity_verification");

  private final AndroidBuckConfig androidBuckConfig;

  public AndroidBinaryFactory(AndroidBuckConfig androidBuckConfig) {
    this.androidBuckConfig = androidBuckConfig;
  }

  public AndroidBinary create(
      ToolchainProvider toolchainProvider,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellPathResolver,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AndroidBinaryGraphEnhancer graphEnhancer,
      DexSplitMode dexSplitMode,
      EnumSet<ExopackageMode> exopackageModes,
      ResourceFilter resourceFilter,
      ImmutableSortedSet<JavaLibrary> rulesToExcludeFromDex,
      ApkConfig apkConfig,
      AndroidBinaryDescriptionArg args) {

    BuildRule keystore = graphBuilder.getRule(args.getKeystore());
    if (!(keystore instanceof Keystore)) {
      throw new HumanReadableException(
          "In %s, keystore='%s' must be a keystore() but was %s().",
          buildTarget, keystore.getFullyQualifiedName(), keystore.getType());
    }

    ProGuardObfuscateStep.SdkProguardType androidSdkProguardConfig =
        args.getAndroidSdkProguardConfig().orElse(ProGuardObfuscateStep.SdkProguardType.NONE);

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);

    AndroidGraphEnhancementResult result = graphEnhancer.createAdditionalBuildables();

    AndroidBinaryFilesInfo filesInfo =
        new AndroidBinaryFilesInfo(result, exopackageModes, args.isPackageAssetLibraries());

    JavaOptionsProvider javaOptionsProvider =
        toolchainProvider.getByName(JavaOptionsProvider.DEFAULT_NAME, JavaOptionsProvider.class);

    Optional<BuildRule> moduleVerification;
    if (args.getAndroidAppModularityResult().isPresent()) {
      moduleVerification =
          Optional.of(
              new AndroidAppModularityVerification(
                  ruleFinder,
                  buildTarget.withFlavors(ANDROID_MODULARITY_VERIFICATION_FLAVOR),
                  projectFilesystem,
                  args.getAndroidAppModularityResult().get(),
                  args.isSkipProguard(),
                  result.getDexFilesInfo().proguardTextFilesPath,
                  result.getPackageableCollection()));
      graphBuilder.addToIndex(moduleVerification.get());
    } else {
      moduleVerification = Optional.empty();
    }

    return new AndroidBinary(
        buildTarget,
        projectFilesystem,
        toolchainProvider.getByName(AndroidSdkLocation.DEFAULT_NAME, AndroidSdkLocation.class),
        toolchainProvider.getByName(
            AndroidPlatformTarget.DEFAULT_NAME, AndroidPlatformTarget.class),
        params,
        ruleFinder,
        Optional.of(args.getProguardJvmArgs()),
        (Keystore) keystore,
        dexSplitMode,
        args.getNoDx(),
        androidSdkProguardConfig,
        args.getOptimizationPasses(),
        args.getProguardConfig(),
        args.isSkipProguard(),
        getRedexOptions(buildTarget, graphBuilder, cellPathResolver, args),
        args.getResourceCompression(),
        args.getCpuFilters(),
        resourceFilter,
        exopackageModes,
        rulesToExcludeFromDex,
        result,
        args.getXzCompressionLevel(),
        args.isPackageAssetLibraries(),
        args.isCompressAssetLibraries(),
        args.getManifestEntries(),
        javaOptionsProvider.getJavaOptions().getJavaRuntimeLauncher(),
        args.getIsCacheable(),
        moduleVerification,
        filesInfo.getDexFilesInfo(),
        filesInfo.getNativeFilesInfo(),
        filesInfo.getResourceFilesInfo(),
        ImmutableSortedSet.copyOf(result.getAPKModuleGraph().getAPKModules()),
        filesInfo.getExopackageInfo(),
        apkConfig.getCompressionLevel());
  }

  private Optional<RedexOptions> getRedexOptions(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      AndroidBinaryDescriptionArg arg) {
    boolean redexRequested = arg.getRedex();
    if (!redexRequested) {
      return Optional.empty();
    }

    Tool redexBinary = androidBuckConfig.getRedexTool(graphBuilder);

    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(buildTarget)
            .setCellPathResolver(cellRoots)
            .setExpanders(MacroExpandersForAndroidRules.MACRO_EXPANDERS)
            .build();
    List<Arg> redexExtraArgs =
        arg.getRedexExtraArgs()
            .stream()
            .map(x -> macrosConverter.convert(x, graphBuilder))
            .collect(Collectors.toList());

    return Optional.of(
        RedexOptions.builder()
            .setRedex(redexBinary)
            .setRedexConfig(arg.getRedexConfig())
            .setRedexExtraArgs(redexExtraArgs)
            .build());
  }
}
