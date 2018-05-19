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
package com.facebook.buck.ide.intellij.lang.android;

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.ide.intellij.BaseIjModuleRule;
import com.facebook.buck.ide.intellij.ModuleBuildContext;
import com.facebook.buck.ide.intellij.model.IjModuleFactoryResolver;
import com.facebook.buck.ide.intellij.model.IjProjectConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;

public abstract class AndroidModuleRule<T extends CommonDescriptionArg>
    extends BaseIjModuleRule<T> {

  private final AndroidProjectType androidProjectType;

  protected AndroidModuleRule(
      ProjectFilesystem projectFilesystem,
      IjModuleFactoryResolver moduleFactoryResolver,
      IjProjectConfig projectConfig,
      AndroidProjectType androidProjectType) {
    super(projectFilesystem, moduleFactoryResolver, projectConfig);
    this.androidProjectType = androidProjectType;
  }

  @Override
  public void apply(TargetNode<T, ?> target, ModuleBuildContext context) {
    context
        .getOrCreateAndroidFacetBuilder()
        .setAndroidProjectType(androidProjectType)
        .setAutogenerateSources(projectConfig.isAutogenerateAndroidFacetSourcesEnabled());
  }
}
