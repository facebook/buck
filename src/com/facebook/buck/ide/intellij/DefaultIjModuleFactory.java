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

import com.facebook.buck.ide.intellij.lang.android.AndroidBinaryModuleRule;
import com.facebook.buck.ide.intellij.lang.android.AndroidLibraryModuleRule;
import com.facebook.buck.ide.intellij.lang.android.AndroidResourceModuleRule;
import com.facebook.buck.ide.intellij.lang.android.RobolectricTestModuleRule;
import com.facebook.buck.ide.intellij.lang.java.JavaBinaryModuleRule;
import com.facebook.buck.ide.intellij.lang.java.JavaLibraryModuleRule;
import com.facebook.buck.ide.intellij.lang.java.JavaTestModuleRule;
import com.facebook.buck.ide.intellij.lang.kotlin.KotlinLibraryModuleRule;
import com.facebook.buck.ide.intellij.lang.kotlin.KotlinTestModuleRule;
import com.facebook.buck.ide.intellij.lang.groovy.GroovyLibraryModuleRule;
import com.facebook.buck.ide.intellij.lang.groovy.GroovyTestModuleRule;
import com.facebook.buck.ide.intellij.lang.cxx.CxxLibraryModuleRule;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class DefaultIjModuleFactory implements IjModuleFactory {

  private static final Logger LOG = Logger.get(DefaultIjModuleFactory.class);

  private final ProjectFilesystem projectFilesystem;
  private final Map<Class<? extends Description<?>>, IjModuleRule<?>> moduleRuleIndex =
      new HashMap<>();

  /**
   * @param moduleFactoryResolver see {@link IjModuleFactoryResolver}.
   */
  public DefaultIjModuleFactory(
      ProjectFilesystem projectFilesystem,
      IjModuleFactoryResolver moduleFactoryResolver,
      IjProjectConfig projectConfig) {
    this.projectFilesystem = projectFilesystem;

    addToIndex(new AndroidBinaryModuleRule(
        projectFilesystem,
        moduleFactoryResolver,
        projectConfig));
    addToIndex(new AndroidLibraryModuleRule(
        projectFilesystem,
        moduleFactoryResolver,
        projectConfig));
    addToIndex(new AndroidResourceModuleRule(
        projectFilesystem,
        moduleFactoryResolver,
        projectConfig));
    addToIndex(new CxxLibraryModuleRule(projectFilesystem, moduleFactoryResolver, projectConfig));
    addToIndex(new JavaBinaryModuleRule(projectFilesystem, moduleFactoryResolver, projectConfig));
    addToIndex(new JavaLibraryModuleRule(projectFilesystem, moduleFactoryResolver, projectConfig));
    addToIndex(new JavaTestModuleRule(projectFilesystem, moduleFactoryResolver, projectConfig));
    addToIndex(new RobolectricTestModuleRule(
        projectFilesystem,
        moduleFactoryResolver,
        projectConfig));
    addToIndex(new GroovyLibraryModuleRule(
        projectFilesystem,
        moduleFactoryResolver,
        projectConfig));
    addToIndex(new GroovyTestModuleRule(projectFilesystem, moduleFactoryResolver, projectConfig));
    addToIndex(new KotlinLibraryModuleRule(
        projectFilesystem,
        moduleFactoryResolver,
        projectConfig));
    addToIndex(new KotlinTestModuleRule(projectFilesystem, moduleFactoryResolver, projectConfig));

    Preconditions.checkState(SupportedTargetTypeRegistry.areTargetTypesEqual(
        moduleRuleIndex.keySet()));
  }

  private void addToIndex(IjModuleRule<?> rule) {
    Preconditions.checkArgument(!moduleRuleIndex.containsKey(rule.getDescriptionClass()));
    Preconditions.checkArgument(SupportedTargetTypeRegistry.isTargetTypeSupported(
        rule.getDescriptionClass()));
    moduleRuleIndex.put(rule.getDescriptionClass(), rule);
  }

  @Override
  public IjModule createModule(
      Path moduleBasePath,
      ImmutableSet<TargetNode<?, ?>> targetNodes) {
    return createModuleUsingSortedTargetNodes(
        moduleBasePath,
        ImmutableSortedSet.copyOf(targetNodes));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private IjModule createModuleUsingSortedTargetNodes(
      Path moduleBasePath,
      ImmutableSortedSet<TargetNode<?, ?>> targetNodes) {
    Preconditions.checkArgument(!targetNodes.isEmpty());

    ImmutableSet<BuildTarget> moduleBuildTargets = targetNodes.stream()
        .map(TargetNode::getBuildTarget)
        .collect(MoreCollectors.toImmutableSet());

    ModuleBuildContext context = new ModuleBuildContext(moduleBuildTargets);

    Set<Class<?>> seenTypes = new HashSet<>();
    for (TargetNode<?, ?> targetNode : targetNodes) {
      Class<?> nodeType = targetNode.getDescription().getClass();
      seenTypes.add(nodeType);
      IjModuleRule<?> rule = Preconditions.checkNotNull(moduleRuleIndex.get(nodeType));
      rule.apply((TargetNode) targetNode, context);
      context.setModuleType(rule.detectModuleType((TargetNode) targetNode));
    }

    if (seenTypes.size() > 1) {
      LOG.debug(
          "Multiple types at the same path. Path: %s, types: %s",
          moduleBasePath,
          seenTypes);
    }

    if (context.isAndroidFacetBuilderPresent()) {
      context.getOrCreateAndroidFacetBuilder().setGeneratedSourcePath(
          createAndroidGenPath(moduleBasePath));
    }

    return IjModule.builder()
        .setModuleBasePath(moduleBasePath)
        .setTargets(moduleBuildTargets)
        .addAllFolders(context.getSourceFolders())
        .putAllDependencies(context.getDependencies())
        .setAndroidFacet(context.getAndroidFacet())
        .addAllExtraClassPathDependencies(context.getExtraClassPathDependencies())
        .addAllGeneratedSourceCodeFolders(context.getGeneratedSourceCodeFolders())
        .setLanguageLevel(context.getJavaLanguageLevel())
        .setModuleType(context.getModuleType())
        .setMetaInfDirectory(context.getMetaInfDirectory())
        .build();
  }

  private Path createAndroidGenPath(Path moduleBasePath) {
    return Paths
        .get(IjAndroidHelper.getAndroidGenDir(projectFilesystem))
        .resolve(moduleBasePath)
        .resolve("gen");
  }
}
