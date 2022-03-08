/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.features.project.intellij.lang.rust;

import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.features.project.intellij.BaseIjModuleRule;
import com.facebook.buck.features.project.intellij.ModuleBuildContext;
import com.facebook.buck.features.project.intellij.depsquery.IjDepsQueryResolver;
import com.facebook.buck.features.project.intellij.model.IjModuleFactoryResolver;
import com.facebook.buck.features.project.intellij.model.IjModuleType;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.features.rust.RustBinaryDescription;
import com.facebook.buck.features.rust.RustBinaryDescriptionArg;
import com.facebook.buck.io.filesystem.ProjectFilesystem;

/** Support for transforming a rust_binary declaration to an intellij module */
public class RustBinaryModuleRule extends BaseIjModuleRule<RustBinaryDescriptionArg> {

  public RustBinaryModuleRule(
      ProjectFilesystem projectFilesystem,
      IjModuleFactoryResolver moduleFactoryResolver,
      IjDepsQueryResolver depsQueryResolver,
      IjProjectConfig projectConfig) {
    super(projectFilesystem, moduleFactoryResolver, depsQueryResolver, projectConfig);
  }

  @Override
  public Class<? extends DescriptionWithTargetGraph<?>> getDescriptionClass() {
    return RustBinaryDescription.class;
  }

  @Override
  public void apply(TargetNode<RustBinaryDescriptionArg> target, ModuleBuildContext context) {
    if (projectConfig.isRustModuleEnabled()) {
      addDepsAndSources(target, false /* wantsPackagePrefix */, context);
    }
  }

  @Override
  public IjModuleType detectModuleType(TargetNode<RustBinaryDescriptionArg> targetNode) {
    return projectConfig.isRustModuleEnabled()
        ? IjModuleType.RUST_MODULE
        : IjModuleType.UNKNOWN_MODULE;
  }
}
