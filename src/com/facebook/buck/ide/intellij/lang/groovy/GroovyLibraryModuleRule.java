/*
 * Copyright 2015-present Facebook, Inc.
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
package com.facebook.buck.ide.intellij.lang.groovy;

import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.ide.intellij.BaseIjModuleRule;
import com.facebook.buck.ide.intellij.ModuleBuildContext;
import com.facebook.buck.ide.intellij.model.IjModuleFactoryResolver;
import com.facebook.buck.ide.intellij.model.IjModuleType;
import com.facebook.buck.ide.intellij.model.IjProjectConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.groovy.GroovyLibraryDescription;

public class GroovyLibraryModuleRule extends BaseIjModuleRule<GroovyLibraryDescription.CoreArg> {

  public GroovyLibraryModuleRule(
      ProjectFilesystem projectFilesystem,
      IjModuleFactoryResolver moduleFactoryResolver,
      IjProjectConfig projectConfig) {
    super(projectFilesystem, moduleFactoryResolver, projectConfig);
  }

  @Override
  public Class<? extends DescriptionWithTargetGraph<?>> getDescriptionClass() {
    return GroovyLibraryDescription.class;
  }

  @Override
  public void apply(
      TargetNode<GroovyLibraryDescription.CoreArg, ?> target, ModuleBuildContext context) {
    addDepsAndSources(target, false /* wantsPackagePrefix */, context);
    context.setCompilerOutputPath(moduleFactoryResolver.getCompilerOutputPath(target));
  }

  @Override
  public IjModuleType detectModuleType(TargetNode<GroovyLibraryDescription.CoreArg, ?> targetNode) {
    return IjModuleType.UNKNOWN_MODULE;
  }
}
