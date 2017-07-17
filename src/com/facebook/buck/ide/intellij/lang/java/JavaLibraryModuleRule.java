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
import com.facebook.buck.ide.intellij.aggregation.AggregationContext;
import com.facebook.buck.ide.intellij.aggregation.AggregationKeys;
import com.facebook.buck.ide.intellij.model.IjModuleFactoryResolver;
import com.facebook.buck.ide.intellij.model.IjModuleType;
import com.facebook.buck.ide.intellij.model.IjProjectConfig;
import com.facebook.buck.ide.intellij.model.folders.JavaResourceFolder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

public class JavaLibraryModuleRule extends BaseIjModuleRule<JavaLibraryDescription.CoreArg> {

  public JavaLibraryModuleRule(
      ProjectFilesystem projectFilesystem,
      IjModuleFactoryResolver moduleFactoryResolver,
      IjProjectConfig projectConfig) {
    super(projectFilesystem, moduleFactoryResolver, projectConfig);
  }

  @Override
  public Class<? extends Description<?>> getDescriptionClass() {
    return JavaLibraryDescription.class;
  }

  @Override
  public void apply(
      TargetNode<JavaLibraryDescription.CoreArg, ?> target, ModuleBuildContext context) {

    Optional<Path> resourcesRoot = target.getConstructorArg().getResourcesRoot();
    // If there is no resources_root, then we use the java src_roots option from .buckconfig, so
    // the default folders generated should work. On the other hand, if there is a resources_root,
    // then for resources under this root, we need to create java-resource folders with the
    // correct relativeOutputPath set.
    Predicate<Map.Entry<Path, Path>> folderInputIndexFilter = null;
    if (resourcesRoot.isPresent()) {
      ImmutableSet<Path> resources = target.getConstructorArg().getResources().stream()
          .map(sourcePath -> (sourcePath instanceof PathSourcePath)
              ? ((PathSourcePath) sourcePath).getRelativePath()
              : null)
          .filter(Objects::nonNull)
          .filter(path -> path.startsWith(resourcesRoot.get()))
          .collect(MoreCollectors.toImmutableSet());

      folderInputIndexFilter = entry -> !resources.contains(entry.getValue());

      addSourceFolders(
          JavaResourceFolder.getFactoryWithResourcesRoot(resourcesRoot.get()),
          getSourceFoldersToInputsIndex(resources),
          true /* wantsPackagePrefix */,
          context);
    }

    addDepsAndSourcesWithFolderInputIndexFilter(
        target, true /* wantsPackagePrefix */, context, folderInputIndexFilter);
    JavaLibraryRuleHelper.addCompiledShadowIfNeeded(projectConfig, target, context);
    context.setJavaLanguageLevel(JavaLibraryRuleHelper.getLanguageLevel(projectConfig, target));
  }

  @Override
  public IjModuleType detectModuleType(TargetNode<JavaLibraryDescription.CoreArg, ?> targetNode) {
    return IjModuleType.JAVA_MODULE;
  }

  @Override
  public void applyDuringAggregation(
      AggregationContext context, TargetNode<JavaLibraryDescription.CoreArg, ?> targetNode) {
    super.applyDuringAggregation(context, targetNode);

    Optional<String> languageLevel =
        JavaLibraryRuleHelper.getLanguageLevel(projectConfig, targetNode);
    if (languageLevel.isPresent()) {
      context.addAggregationKey(AggregationKeys.JAVA_LANGUAGE_LEVEL, languageLevel);
    }
  }
}
