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
import com.facebook.buck.ide.intellij.model.DependencyType;
import com.facebook.buck.ide.intellij.model.IjModuleFactoryResolver;
import com.facebook.buck.ide.intellij.model.IjModuleType;
import com.facebook.buck.ide.intellij.model.IjProjectConfig;
import com.facebook.buck.ide.intellij.ModuleBuildContext;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaBinaryDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetNode;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

public class JavaBinaryModuleRule
    extends BaseIjModuleRule<JavaBinaryDescription.Args> {

  public JavaBinaryModuleRule(
      ProjectFilesystem projectFilesystem,
      IjModuleFactoryResolver moduleFactoryResolver,
      IjProjectConfig projectConfig) {
    super(projectFilesystem, moduleFactoryResolver, projectConfig);
  }

  @Override
  public Class<? extends Description<?>> getDescriptionClass() {
    return JavaBinaryDescription.class;
  }

  @Override
  public void apply(
      TargetNode<JavaBinaryDescription.Args, ?> target,
      ModuleBuildContext context) {
    context.addDeps(target.getBuildDeps(), DependencyType.PROD);
    saveMetaInfDirectoryForIntellijPlugin(target, context);
  }

  private void saveMetaInfDirectoryForIntellijPlugin(
      TargetNode<JavaBinaryDescription.Args, ?> target,
      ModuleBuildContext context) {
    Set<String> intellijLibraries = projectConfig.getIntellijSdkTargets();
    for (BuildTarget dep : target.getBuildDeps()) {
      Optional<Path> metaInfDirectory = target.getConstructorArg().metaInfDirectory;
      if (metaInfDirectory.isPresent() &&
          intellijLibraries.contains(dep.getFullyQualifiedName())) {
        context.setMetaInfDirectory(metaInfDirectory.get());
        break;
      }
    }
  }

  @Override
  public IjModuleType detectModuleType(TargetNode<JavaBinaryDescription.Args, ?> targetNode) {
    Set<String> intellijLibraries = projectConfig.getIntellijSdkTargets();
    for (BuildTarget dep : targetNode.getBuildDeps()) {
      Optional<Path> metaInfDirectory = targetNode.getConstructorArg().metaInfDirectory;
      if (metaInfDirectory.isPresent() &&
          intellijLibraries.contains(dep.getFullyQualifiedName())) {
        return IjModuleType.INTELLIJ_PLUGIN_MODULE;
      }
    }
    return IjModuleType.JAVA_MODULE;
  }
}
