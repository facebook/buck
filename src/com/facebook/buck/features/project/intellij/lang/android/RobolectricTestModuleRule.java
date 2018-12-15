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
package com.facebook.buck.features.project.intellij.lang.android;

import com.facebook.buck.android.RobolectricTestDescription;
import com.facebook.buck.android.RobolectricTestDescriptionArg;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.features.project.intellij.ModuleBuildContext;
import com.facebook.buck.features.project.intellij.aggregation.AggregationContext;
import com.facebook.buck.features.project.intellij.lang.java.JavaLibraryRuleHelper;
import com.facebook.buck.features.project.intellij.model.IjModuleFactoryResolver;
import com.facebook.buck.features.project.intellij.model.IjModuleType;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;

public class RobolectricTestModuleRule extends AndroidModuleRule<RobolectricTestDescriptionArg> {

  public RobolectricTestModuleRule(
      ProjectFilesystem projectFilesystem,
      IjModuleFactoryResolver moduleFactoryResolver,
      IjProjectConfig projectConfig) {
    super(projectFilesystem, moduleFactoryResolver, projectConfig, AndroidProjectType.LIBRARY);
  }

  @Override
  public Class<? extends DescriptionWithTargetGraph<?>> getDescriptionClass() {
    return RobolectricTestDescription.class;
  }

  @Override
  public void apply(TargetNode<RobolectricTestDescriptionArg> target, ModuleBuildContext context) {
    super.apply(target, context);
    addDepsAndTestSources(target, true /* wantsPackagePrefix */, context);
    JavaLibraryRuleHelper.addCompiledShadowIfNeeded(projectConfig, target, context);
    context.setJavaLanguageLevel(JavaLibraryRuleHelper.getLanguageLevel(projectConfig, target));
  }

  @Override
  public IjModuleType detectModuleType(TargetNode<RobolectricTestDescriptionArg> targetNode) {
    return IjModuleType.ANDROID_MODULE;
  }

  @Override
  public void applyDuringAggregation(
      AggregationContext context, TargetNode<RobolectricTestDescriptionArg> targetNode) {
    super.applyDuringAggregation(context, targetNode);
    JavaLibraryRuleHelper.addLanguageAggregationKeyIfNeeded(projectConfig, targetNode, context);
  }
}
