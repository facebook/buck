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

package com.facebook.buck.android;

import com.facebook.buck.dalvik.ZipSplitter;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AbstractBuildRuleFactory;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;

import java.util.List;

public class AndroidBinaryBuildRuleFactory extends AbstractBuildRuleFactory<AndroidBinaryRule.Builder> {

  @Override
  public AndroidBinaryRule.Builder newBuilder(AbstractBuildRuleBuilderParams params) {
    return AndroidBinaryRule.newAndroidBinaryRuleBuilder(params);
  }

  @Override
  protected void amendBuilder(AndroidBinaryRule.Builder builder,
      BuildRuleFactoryParams params) throws NoSuchBuildTargetException {
    // manifest
    builder.setManifest(params.getRequiredSourcePath("manifest", builder));

    // target
    String target = params.getRequiredStringAttribute("target");
    builder.setTarget(target);

    // keystore
    BuildTarget keystore = params.getRequiredBuildTarget("keystore");
    builder.setKeystore(keystore);

    // classpath_deps
    for (String classpathDep : params.getOptionalListAttribute("classpath_deps")) {
      BuildTarget classpathDepTarget = params.resolveBuildTarget(classpathDep);
      builder.addClasspathDep(classpathDepTarget);
    }

    // package_type
    // Note that it is not required for the user to supply this attribute, but buck.py should
    // supply 'debug' if the user has not supplied a value.
    String packageType = params.getRequiredStringAttribute("package_type");
    builder.setPackageType(packageType);

    // no_dx
    for (String noDx : params.getOptionalListAttribute("no_dx")) {
      BuildTarget buildTarget = params.resolveBuildTarget(noDx);
      builder.addBuildRuleToExcludeFromDex(buildTarget);
    }

    // use_split_dex
    boolean useSplitDex = params.getBooleanAttribute("use_split_dex");

    ZipSplitter.DexSplitStrategy dexSplitStrategy = params.getBooleanAttribute("minimize_primary_dex_size")
        ? ZipSplitter.DexSplitStrategy.MINIMIZE_PRIMARY_DEX_SIZE
        : ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE;

    // disable_pre_dex
    builder.setDisablePreDex(params.getBooleanAttribute("disable_pre_dex"));

    // dex_compression
    DexStore dexStore =
        "xz".equals(params.getRequiredStringAttribute("dex_compression")) ?
            DexStore.XZ :
            DexStore.JAR;

    boolean useLinearAllocSplitDex = params.getBooleanAttribute("use_linear_alloc_split_dex");
    List<String> primaryDexPatterns = params.getOptionalListAttribute("primary_dex_patterns");
    long linearAllocHardLimit = params.getRequiredLongAttribute("linear_alloc_hard_limit");
    Optional<SourcePath> primaryDexClassesFile = params.getOptionalSourcePath(
        "primary_dex_classes_file", builder);

    builder.setDexSplitMode(new DexSplitMode(
        useSplitDex,
        dexSplitStrategy,
        dexStore,
        useLinearAllocSplitDex,
        linearAllocHardLimit,
        primaryDexPatterns,
        primaryDexClassesFile));

    // use_android_proguard_config_with_optimizations
    boolean useAndroidProguardConfigWithOptimizations =
        params.getBooleanAttribute("use_android_proguard_config_with_optimizations");
    builder.setUseAndroidProguardConfigWithOptimizations(useAndroidProguardConfigWithOptimizations);

    // proguard_config
    Optional<SourcePath> proguardConfig = params.getOptionalSourcePath("proguard_config", builder);
    builder.setProguardConfig(proguardConfig);

    // resource_compression
    Optional<String> resourceCompression = params.getOptionalStringAttribute("resource_compression");
    if (resourceCompression.isPresent()) {
      builder.setResourceCompressionMode(resourceCompression.get());
    }

    // resource_filter
    List<String> resourceFilter = params.getOptionalListAttribute("resource_filter");
    builder.setResourceFilter(new FilterResourcesStep.ResourceFilter(resourceFilter));

    // cpu_filters
    List<String> cpuFilters = params.getOptionalListAttribute("cpu_filters");
    for (String filter: cpuFilters) {
      builder.addCpuFilter(filter);
    }

    // preprocess_java_classes_deps
    for (String dep : params.getOptionalListAttribute("preprocess_java_classes_deps")) {
      BuildTarget buildTarget = params.resolveBuildTarget(dep);
      builder.addPreprocessJavaClassesDep(buildTarget);
    }

    // preprocess_java_classes_bash
    builder.setPreprocessJavaClassesBash(params.getOptionalStringAttribute(
        "preprocess_java_classes_bash"));
  }

}
