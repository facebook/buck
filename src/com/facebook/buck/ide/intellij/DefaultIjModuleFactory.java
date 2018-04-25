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

package com.facebook.buck.ide.intellij;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.ide.intellij.model.IjModule;
import com.facebook.buck.ide.intellij.model.IjModuleFactory;
import com.facebook.buck.ide.intellij.model.IjModuleRule;
import com.facebook.buck.ide.intellij.model.folders.ExcludeFolder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class DefaultIjModuleFactory implements IjModuleFactory {

  private static final Logger LOG = Logger.get(DefaultIjModuleFactory.class);

  private final ProjectFilesystem projectFilesystem;
  private final SupportedTargetTypeRegistry typeRegistry;

  public DefaultIjModuleFactory(
      ProjectFilesystem projectFilesystem, SupportedTargetTypeRegistry typeRegistry) {
    this.projectFilesystem = projectFilesystem;
    this.typeRegistry = typeRegistry;
  }

  @Override
  @SuppressWarnings(
      "rawtypes") // https://github.com/immutables/immutables/issues/548 requires us to use
  // TargetNode not TargetNode<?, ?>
  public IjModule createModule(
      Path moduleBasePath, ImmutableSet<TargetNode> targetNodes, Set<Path> excludes) {
    return createModuleUsingSortedTargetNodes(
        moduleBasePath, ImmutableSortedSet.copyOf(targetNodes), excludes);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private IjModule createModuleUsingSortedTargetNodes(
      Path moduleBasePath, ImmutableSortedSet<TargetNode> targetNodes, Set<Path> excludes) {
    Preconditions.checkArgument(!targetNodes.isEmpty());

    ImmutableSet<BuildTarget> moduleBuildTargets =
        targetNodes.stream().map(TargetNode::getBuildTarget).collect(ImmutableSet.toImmutableSet());

    ModuleBuildContext context = new ModuleBuildContext(moduleBuildTargets);

    Set<Class<?>> seenTypes = new HashSet<>();
    for (TargetNode<?, ?> targetNode : targetNodes) {
      Class<?> nodeType = targetNode.getDescription().getClass();
      seenTypes.add(nodeType);
      IjModuleRule<?> rule =
          Preconditions.checkNotNull(typeRegistry.getModuleRuleByTargetNodeType(nodeType));
      rule.apply((TargetNode) targetNode, context);
      context.setModuleType(rule.detectModuleType((TargetNode) targetNode));
    }

    if (seenTypes.size() > 1) {
      LOG.debug("Multiple types at the same path. Path: %s, types: %s", moduleBasePath, seenTypes);
    }

    if (context.isAndroidFacetBuilderPresent()) {
      context
          .getOrCreateAndroidFacetBuilder()
          .setGeneratedSourcePath(
              IjAndroidHelper.createAndroidGenPath(projectFilesystem, moduleBasePath));
    }

    excludes
        .stream()
        .map(moduleBasePath::resolve)
        .map(ExcludeFolder::new)
        .forEach(context::addSourceFolder);

    return IjModule.builder()
        .setModuleBasePath(moduleBasePath)
        .setTargets(moduleBuildTargets)
        .setNonSourceBuildTargets(context.getNonSourceBuildTargets())
        .addAllFolders(context.getSourceFolders())
        .putAllDependencies(context.getDependencies())
        .setAndroidFacet(context.getAndroidFacet())
        .addAllExtraClassPathDependencies(context.getExtraClassPathDependencies())
        .addAllExtraModuleDependencies(context.getExtraModuleDependencies())
        .addAllGeneratedSourceCodeFolders(context.getGeneratedSourceCodeFolders())
        .setLanguageLevel(context.getJavaLanguageLevel())
        .setModuleType(context.getModuleType())
        .setMetaInfDirectory(context.getMetaInfDirectory())
        .setCompilerOutputPath(context.getCompilerOutputPath())
        .build();
  }
}
