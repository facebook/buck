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

import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.AndroidResourceDescriptionArg;
import com.facebook.buck.ide.intellij.ModuleBuildContext;
import com.facebook.buck.ide.intellij.model.DependencyType;
import com.facebook.buck.ide.intellij.model.IjModuleAndroidFacet;
import com.facebook.buck.ide.intellij.model.IjModuleFactoryResolver;
import com.facebook.buck.ide.intellij.model.IjModuleType;
import com.facebook.buck.ide.intellij.model.IjProjectConfig;
import com.facebook.buck.ide.intellij.model.folders.ExcludeFolder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetNode;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class AndroidResourceModuleRule extends AndroidModuleRule<AndroidResourceDescriptionArg> {

  public AndroidResourceModuleRule(
      ProjectFilesystem projectFilesystem,
      IjModuleFactoryResolver moduleFactoryResolver,
      IjProjectConfig projectConfig) {
    super(projectFilesystem, moduleFactoryResolver, projectConfig, true);
  }

  @Override
  public Class<? extends Description<?>> getDescriptionClass() {
    return AndroidResourceDescription.class;
  }

  @Override
  public void apply(
      TargetNode<AndroidResourceDescriptionArg, ?> target, ModuleBuildContext context) {
    super.apply(target, context);

    IjModuleAndroidFacet.Builder androidFacetBuilder = context.getOrCreateAndroidFacetBuilder();

    Optional<Path> assets = moduleFactoryResolver.getAssetsPath(target);
    if (assets.isPresent()) {
      androidFacetBuilder.addAssetPaths(assets.get());
    }

    Optional<Path> resources = moduleFactoryResolver.getAndroidResourcePath(target);
    ImmutableSet<Path> resourceFolders;
    if (resources.isPresent()) {
      resourceFolders = ImmutableSet.of(resources.get());

      androidFacetBuilder.addAllResourcePaths(resourceFolders);

      List<String> excludedResourcePaths = projectConfig.getExcludedResourcePaths();

      for (Path resourceFolder : resourceFolders) {
        context.addSourceFolder(new AndroidResourceFolder(resourceFolder));

        excludedResourcePaths
            .stream()
            .map((file) -> resourceFolder.resolve(file))
            .forEach((folder) -> context.addSourceFolder(new ExcludeFolder(folder)));
      }
    } else {
      resourceFolders = ImmutableSet.of();
    }

    androidFacetBuilder.setPackageName(target.getConstructorArg().getPackage());

    Optional<Path> dummyRDotJavaClassPath = moduleFactoryResolver.getDummyRDotJavaPath(target);
    if (dummyRDotJavaClassPath.isPresent()) {
      context.addExtraClassPathDependency(dummyRDotJavaClassPath.get());
    }

    context.addDeps(resourceFolders, target.getBuildDeps(), DependencyType.PROD);
  }

  @Override
  public IjModuleType detectModuleType(TargetNode<AndroidResourceDescriptionArg, ?> targetNode) {
    return IjModuleType.ANDROID_RESOURCES_MODULE;
  }
}
