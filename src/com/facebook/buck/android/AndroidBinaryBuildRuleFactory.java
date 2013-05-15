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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.AbstractBuildRuleFactory;
import com.facebook.buck.parser.BuildRuleFactoryParams;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.ParseContext;
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.util.ZipSplitter;
import com.google.common.base.Optional;
import com.google.common.base.Strings;

import java.util.List;

public class AndroidBinaryBuildRuleFactory extends AbstractBuildRuleFactory {

  @Override
  public AndroidBinaryRule.Builder newBuilder() {
    return AndroidBinaryRule.newAndroidBinaryRuleBuilder();
  }

  @Override
  protected void amendBuilder(AbstractBuildRuleBuilder abstractBuilder,
      BuildRuleFactoryParams params) throws NoSuchBuildTargetException {
    AndroidBinaryRule.Builder builder = ((AndroidBinaryRule.Builder)abstractBuilder);

    // manifest
    String manifestAttribute = params.getRequiredStringAttribute("manifest");
    String manifestPath = params.resolveFilePathRelativeToBuildFileDirectory(manifestAttribute);
    builder.setManifest(manifestPath);

    // target
    String target = params.getRequiredStringAttribute("target");
    builder.setTarget(target);

    // keystore_properties
    String keystoreProperties = params.getRequiredStringAttribute("keystore_properties");
    String keystorePropertiesPath = params.resolveFilePathRelativeToBuildFileDirectory(
        keystoreProperties);
    builder.setKeystorePropertiesPath(keystorePropertiesPath);

    // package_type
    // Note that it is not required for the user to supply this attribute, but buck.py should
    // supply 'debug' if the user has not supplied a value.
    String packageType = params.getRequiredStringAttribute("package_type");
    builder.setPackageType(packageType);

    // no_dx
    ParseContext buildFileParseContext = ParseContext.forBaseName(params.target.getBaseName());
    for (String noDx : params.getOptionalListAttribute("no_dx")) {
      BuildTarget buildTarget = params.buildTargetParser.parse(noDx, buildFileParseContext);
      builder.addBuildRuleToExcludeFromDex(buildTarget.getFullyQualifiedName());
    }

    // use_split_dex
    boolean useSplitDex = params.getBooleanAttribute("use_split_dex");

    ZipSplitter.DexSplitStrategy dexSplitStrategy = params.getBooleanAttribute("minimize_primary_dex_size")
            ? ZipSplitter.DexSplitStrategy.MINIMIZE_PRIMARY_DEX_SIZE
            : ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE;

    builder.setDexSplitMode(new AndroidBinaryRule.DexSplitMode(useSplitDex, dexSplitStrategy));


    // use_android_proguard_config_with_optimizations
    boolean useAndroidProguardConfigWithOptimizations =
        params.getBooleanAttribute("use_android_proguard_config_with_optimizations");
    builder.setUseAndroidProguardConfigWithOptimizations(useAndroidProguardConfigWithOptimizations);

    // proguard_config
    Optional<String> proguardConfig = params.getOptionalStringAttribute("proguard_config");
    if (proguardConfig.isPresent()) {
      String proguardConfigPath = params.resolveFilePathRelativeToBuildFileDirectory(
          proguardConfig.get());
      builder.setProguardConfig(proguardConfigPath);
    }

    // compress_resources
    boolean compressResources = params.getBooleanAttribute("compress_resources");
    builder.setCompressResources(compressResources);

    // primary_dex_substrings
    List<String> primaryDexSubstrings = params.getOptionalListAttribute("primary_dex_substrings");
    builder.addPrimaryDexSubstrings(primaryDexSubstrings);

    // resource_filter
    Optional<String> resourceFilter = params.getOptionalStringAttribute("resource_filter");
    if (resourceFilter.isPresent()) {
      builder.setResourceFilter(Strings.emptyToNull(resourceFilter.get().trim()));
    }
  }

}
