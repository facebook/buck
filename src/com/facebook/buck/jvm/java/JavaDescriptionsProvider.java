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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.description.Description;
import com.facebook.buck.core.description.DescriptionCreationContext;
import com.facebook.buck.core.model.targetgraph.DescriptionProvider;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.sandbox.SandboxConfig;
import com.facebook.buck.support.cli.config.CliConfig;
import java.util.Arrays;
import java.util.Collection;
import org.pf4j.Extension;

@Extension
public class JavaDescriptionsProvider implements DescriptionProvider {

  @Override
  public Collection<Description<?>> getDescriptions(DescriptionCreationContext context) {
    ToolchainProvider toolchainProvider = context.getToolchainProvider();
    BuckConfig buckConfig = context.getBuckConfig();
    JavaBuckConfig javaConfig = buckConfig.getView(JavaBuckConfig.class);
    JavaCDBuckConfig javaCDBuckConfig = buckConfig.getView(JavaCDBuckConfig.class);
    DownwardApiConfig downwardApiConfig = buckConfig.getView(DownwardApiConfig.class);
    CliConfig cliConfig = buckConfig.getView(CliConfig.class);
    SandboxConfig sandboxConfig = buckConfig.getView(SandboxConfig.class);
    RemoteExecutionConfig reConfig = buckConfig.getView(RemoteExecutionConfig.class);

    return Arrays.asList(
        new JarGenruleDescription(
            toolchainProvider,
            sandboxConfig,
            reConfig,
            downwardApiConfig,
            cliConfig,
            context.getSandboxExecutionStrategy()),
        new JavaBinaryDescription(toolchainProvider, javaConfig, downwardApiConfig),
        new JavaAnnotationProcessorDescription(),
        new JavaPluginDescription(),
        new JavaLibraryDescription(
            toolchainProvider, javaConfig, javaCDBuckConfig, downwardApiConfig),
        new JavaTestRunnerDescription(
            toolchainProvider, javaConfig, javaCDBuckConfig, downwardApiConfig),
        new JavaTestDescription(toolchainProvider, javaConfig, javaCDBuckConfig, downwardApiConfig),
        new KeystoreDescription());
  }
}
