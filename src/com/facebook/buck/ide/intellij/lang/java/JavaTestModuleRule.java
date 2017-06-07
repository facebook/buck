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
package com.facebook.buck.ide.intellij.lang.java;

import com.facebook.buck.ide.intellij.BaseIjModuleRule;
import com.facebook.buck.ide.intellij.ModuleBuildContext;
import com.facebook.buck.ide.intellij.model.IjModuleFactoryResolver;
import com.facebook.buck.ide.intellij.model.IjModuleType;
import com.facebook.buck.ide.intellij.model.IjProjectConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetNode;

public class JavaTestModuleRule extends BaseIjModuleRule<JavaTestDescription.CoreArg> {

  public JavaTestModuleRule(
      ProjectFilesystem projectFilesystem,
      IjModuleFactoryResolver moduleFactoryResolver,
      IjProjectConfig projectConfig) {
    super(projectFilesystem, moduleFactoryResolver, projectConfig);
  }

  @Override
  public Class<? extends Description<?>> getDescriptionClass() {
    return JavaTestDescription.class;
  }

  @Override
  public void apply(TargetNode<JavaTestDescription.CoreArg, ?> target, ModuleBuildContext context) {
    addDepsAndTestSources(target, true /* wantsPackagePrefix */, context);
    JavaLibraryRuleHelper.addCompiledShadowIfNeeded(projectConfig, target, context);
  }

  @Override
  public IjModuleType detectModuleType(TargetNode<JavaTestDescription.CoreArg, ?> targetNode) {
    return IjModuleType.JAVA_MODULE;
  }
}
