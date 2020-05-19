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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.description.Description;
import com.facebook.buck.core.description.DescriptionCreationContext;
import com.facebook.buck.core.model.targetgraph.DescriptionProvider;
import com.facebook.buck.core.resources.ResourcesConfig;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.kotlin.KotlinBuckConfig;
import com.facebook.buck.jvm.scala.ScalaBuckConfig;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.sandbox.SandboxConfig;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.util.environment.Platform;
import java.util.Arrays;
import java.util.Collection;
import org.pf4j.Extension;

@Extension
public class AndroidDescriptionsProvider implements DescriptionProvider {

  @Override
  public Collection<Description<?>> getDescriptions(DescriptionCreationContext context) {
    SandboxExecutionStrategy sandboxExecutionStrategy = context.getSandboxExecutionStrategy();
    ToolchainProvider toolchainProvider = context.getToolchainProvider();
    BuckConfig buckConfig = context.getBuckConfig();
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(buckConfig);
    JavaBuckConfig javaConfig = buckConfig.getView(JavaBuckConfig.class);
    ResourcesConfig resourcesConfig = buckConfig.getView(ResourcesConfig.class);

    ProGuardConfig proGuardConfig = new ProGuardConfig(buckConfig);
    DxConfig dxConfig = new DxConfig(buckConfig);
    ScalaBuckConfig scalaConfig = new ScalaBuckConfig(buckConfig);
    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);
    AndroidBuckConfig androidBuckConfig = new AndroidBuckConfig(buckConfig, Platform.detect());
    DownwardApiConfig downwardApiConfig = buckConfig.getView(DownwardApiConfig.class);
    SandboxConfig sandboxConfig = buckConfig.getView(SandboxConfig.class);
    RemoteExecutionConfig reConfig = buckConfig.getView(RemoteExecutionConfig.class);

    AndroidLibraryCompilerFactory defaultAndroidCompilerFactory =
        new DefaultAndroidLibraryCompilerFactory(javaConfig, scalaConfig, kotlinBuckConfig);
    AndroidInstallConfig androidInstallConfig = new AndroidInstallConfig(buckConfig);
    AndroidManifestFactory androidManifestFactory = new AndroidManifestFactory();

    return Arrays.asList(
        new AndroidAarDescription(
            androidManifestFactory, cxxBuckConfig, downwardApiConfig, toolchainProvider),
        new AndroidManifestDescription(androidManifestFactory),
        new AndroidAppModularityDescription(),
        new AndroidBinaryDescription(
            javaConfig,
            proGuardConfig,
            androidBuckConfig,
            androidInstallConfig,
            cxxBuckConfig,
            dxConfig,
            downwardApiConfig,
            toolchainProvider,
            new AndroidBinaryGraphEnhancerFactory(),
            new AndroidBinaryFactory(androidBuckConfig)),
        new AndroidBuildConfigDescription(toolchainProvider),
        new AndroidBundleDescription(
            javaConfig,
            proGuardConfig,
            androidBuckConfig,
            buckConfig,
            cxxBuckConfig,
            dxConfig,
            downwardApiConfig,
            toolchainProvider,
            new AndroidBinaryGraphEnhancerFactory(),
            new AndroidBundleFactory(androidBuckConfig)),
        new AndroidInstrumentationApkDescription(
            javaConfig,
            proGuardConfig,
            cxxBuckConfig,
            dxConfig,
            toolchainProvider,
            androidBuckConfig,
            downwardApiConfig),
        new AndroidInstrumentationTestDescription(buckConfig, toolchainProvider),
        new AndroidLibraryDescription(javaConfig, defaultAndroidCompilerFactory, toolchainProvider),
        new AndroidPrebuiltAarDescription(toolchainProvider, androidBuckConfig),
        new AndroidResourceDescription(toolchainProvider, androidBuckConfig),
        new RobolectricTestDescription(
            toolchainProvider, javaConfig, defaultAndroidCompilerFactory),
        new PrebuiltNativeLibraryDescription(),
        new NdkLibraryDescription(toolchainProvider, resourcesConfig.getConcurrencyLimit()),
        new NdkToolchainDescription(),
        new GenAidlDescription(),
        new ApkGenruleDescription(
            toolchainProvider,
            sandboxConfig,
            reConfig,
            downwardApiConfig,
            sandboxExecutionStrategy));
  }
}
