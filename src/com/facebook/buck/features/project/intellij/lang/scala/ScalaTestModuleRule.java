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
package com.facebook.buck.features.project.intellij.lang.scala;

import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.features.project.intellij.BaseIjModuleRule;
import com.facebook.buck.features.project.intellij.ModuleBuildContext;
import com.facebook.buck.features.project.intellij.model.IjModuleFactoryResolver;
import com.facebook.buck.features.project.intellij.model.IjModuleType;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.scala.ScalaTestDescription;
import com.facebook.buck.jvm.scala.ScalaTestDescriptionArg;

public class ScalaTestModuleRule extends BaseIjModuleRule<ScalaTestDescriptionArg> {

  public ScalaTestModuleRule(
      ProjectFilesystem projectFilesystem,
      IjModuleFactoryResolver moduleFactoryResolver,
      IjProjectConfig projectConfig) {
    super(projectFilesystem, moduleFactoryResolver, projectConfig);
  }

  @Override
  public Class<? extends DescriptionWithTargetGraph<?>> getDescriptionClass() {
    return ScalaTestDescription.class;
  }

  @Override
  public void apply(TargetNode<ScalaTestDescriptionArg> target, ModuleBuildContext context) {
    addDepsAndTestSources(target, false /* wantsPackagePrefix */, context);
  }

  @Override
  public IjModuleType detectModuleType(TargetNode<ScalaTestDescriptionArg> targetNode) {
    return IjModuleType.UNKNOWN_MODULE;
  }
}
