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

import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.ide.intellij.IjModuleAndroidFacet;
import com.facebook.buck.ide.intellij.IjModuleFactoryResolver;
import com.facebook.buck.ide.intellij.IjModuleType;
import com.facebook.buck.ide.intellij.IjProjectConfig;
import com.facebook.buck.ide.intellij.ModuleBuildContext;
import com.facebook.buck.ide.intellij.lang.java.JavaLibraryRuleHelper;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetNode;

import java.nio.file.Path;
import java.util.Optional;

public class AndroidLibraryModuleRule
    extends AndroidModuleRule<AndroidLibraryDescription.Arg> {

  public AndroidLibraryModuleRule(
      ProjectFilesystem projectFilesystem,
      IjModuleFactoryResolver moduleFactoryResolver,
      IjProjectConfig projectConfig) {
    super(projectFilesystem, moduleFactoryResolver, projectConfig, true);
  }

  @Override
  public Class<? extends Description<?>> getDescriptionClass() {
    return AndroidLibraryDescription.class;
  }

  @Override
  public void apply(TargetNode<AndroidLibraryDescription.Arg, ?> target,
      ModuleBuildContext context) {
    super.apply(target, context);
    addDepsAndSources(
        target,
        true /* wantsPackagePrefix */,
        context);
    JavaLibraryRuleHelper.addCompiledShadowIfNeeded(projectConfig, target, context);
    Optional<Path> dummyRDotJavaClassPath = moduleFactoryResolver.getDummyRDotJavaPath(target);
    if (dummyRDotJavaClassPath.isPresent()) {
      context.addExtraClassPathDependency(dummyRDotJavaClassPath.get());
    }

    IjModuleAndroidFacet.Builder builder = context.getOrCreateAndroidFacetBuilder();
    Optional<Path> manifestPath = moduleFactoryResolver.getLibraryAndroidManifestPath(target);
    if (manifestPath.isPresent()) {
      builder.setManifestPath(manifestPath.get());
    }
  }

  @Override
  public IjModuleType detectModuleType(TargetNode<AndroidLibraryDescription.Arg, ?> targetNode) {
    return IjModuleType.ANDROID_MODULE;
  }
}
