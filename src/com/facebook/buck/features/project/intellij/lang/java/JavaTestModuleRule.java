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
package com.facebook.buck.features.project.intellij.lang.java;

import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.features.project.intellij.BaseIjModuleRule;
import com.facebook.buck.features.project.intellij.ModuleBuildContext;
import com.facebook.buck.features.project.intellij.model.IjModuleFactoryResolver;
import com.facebook.buck.features.project.intellij.model.IjModuleType;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.features.project.intellij.model.folders.IjResourceFolderType;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;

public class JavaTestModuleRule extends BaseIjModuleRule<JavaTestDescription.CoreArg> {

  private final JavaPackageFinder packageFinder;

  public JavaTestModuleRule(
      ProjectFilesystem projectFilesystem,
      IjModuleFactoryResolver moduleFactoryResolver,
      IjProjectConfig projectConfig,
      JavaPackageFinder packageFinder) {
    super(projectFilesystem, moduleFactoryResolver, projectConfig);
    this.packageFinder = packageFinder;
  }

  @Override
  public Class<? extends DescriptionWithTargetGraph<?>> getDescriptionClass() {
    return JavaTestDescription.class;
  }

  @Override
  public void apply(TargetNode<JavaTestDescription.CoreArg> target, ModuleBuildContext context) {
    Optional<Path> presetResourcesRoot = target.getConstructorArg().getResourcesRoot();
    ImmutableSortedSet<SourcePath> resources = target.getConstructorArg().getResources();
    ImmutableSet<Path> resourcePaths;
    if (presetResourcesRoot.isPresent()) {
      resourcePaths =
          getResourcePaths(target.getConstructorArg().getResources(), presetResourcesRoot.get());
      addResourceFolders(
          IjResourceFolderType.JAVA_TEST_RESOURCE,
          resourcePaths,
          presetResourcesRoot.get(),
          context);
    } else {
      resourcePaths = getResourcePaths(resources);
      ImmutableMultimap<Path, Path> resourcesRootsToResources =
          getResourcesRootsToResources(packageFinder, resourcePaths);
      for (Path resourcesRoot : resourcesRootsToResources.keySet()) {
        addResourceFolders(
            IjResourceFolderType.JAVA_TEST_RESOURCE,
            resourcesRootsToResources.get(resourcesRoot),
            resourcesRoot,
            context);
      }
    }
    addDepsAndTestSources(target, true /* wantsPackagePrefix */, context, resourcePaths);
    JavaLibraryRuleHelper.addCompiledShadowIfNeeded(projectConfig, target, context);
  }

  @Override
  public IjModuleType detectModuleType(TargetNode<JavaTestDescription.CoreArg> targetNode) {
    return IjModuleType.JAVA_MODULE;
  }
}
