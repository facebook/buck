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
package com.facebook.buck.features.project.intellij;

import com.facebook.buck.android.AndroidBinaryDescription;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.RobolectricTestDescription;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxTestDescription;
import com.facebook.buck.features.project.intellij.lang.android.AndroidBinaryModuleRule;
import com.facebook.buck.features.project.intellij.lang.android.AndroidLibraryModuleRule;
import com.facebook.buck.features.project.intellij.lang.android.AndroidResourceModuleRule;
import com.facebook.buck.features.project.intellij.lang.android.RobolectricTestModuleRule;
import com.facebook.buck.features.project.intellij.lang.cxx.CxxLibraryModuleRule;
import com.facebook.buck.features.project.intellij.lang.cxx.CxxTestModuleRule;
import com.facebook.buck.features.project.intellij.lang.groovy.GroovyLibraryModuleRule;
import com.facebook.buck.features.project.intellij.lang.groovy.GroovyTestModuleRule;
import com.facebook.buck.features.project.intellij.lang.java.JavaBinaryModuleRule;
import com.facebook.buck.features.project.intellij.lang.java.JavaLibraryModuleRule;
import com.facebook.buck.features.project.intellij.lang.java.JavaTestModuleRule;
import com.facebook.buck.features.project.intellij.lang.kotlin.KotlinLibraryModuleRule;
import com.facebook.buck.features.project.intellij.lang.kotlin.KotlinTestModuleRule;
import com.facebook.buck.features.project.intellij.lang.python.PythonLibraryModuleRule;
import com.facebook.buck.features.project.intellij.lang.python.PythonTestModuleRule;
import com.facebook.buck.features.project.intellij.lang.scala.ScalaLibraryModuleRule;
import com.facebook.buck.features.project.intellij.lang.scala.ScalaTestModuleRule;
import com.facebook.buck.features.project.intellij.model.IjModuleFactoryResolver;
import com.facebook.buck.features.project.intellij.model.IjModuleRule;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.features.python.PythonLibraryDescription;
import com.facebook.buck.features.python.PythonTestDescription;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.groovy.GroovyLibraryDescription;
import com.facebook.buck.jvm.groovy.GroovyTestDescription;
import com.facebook.buck.jvm.java.JavaBinaryDescription;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.kotlin.KotlinLibraryDescription;
import com.facebook.buck.jvm.kotlin.KotlinTestDescription;
import com.facebook.buck.jvm.scala.ScalaLibraryDescription;
import com.facebook.buck.jvm.scala.ScalaTestDescription;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class SupportedTargetTypeRegistry {
  /** These target types are mapped onto .iml module files. */
  private static final ImmutableSet<Class<? extends DescriptionWithTargetGraph<?>>>
      SUPPORTED_MODULE_DESCRIPTION_CLASSES =
          ImmutableSet.of(
              AndroidBinaryDescription.class,
              AndroidLibraryDescription.class,
              AndroidResourceDescription.class,
              CxxLibraryDescription.class,
              CxxTestDescription.class,
              JavaBinaryDescription.class,
              JavaLibraryDescription.class,
              JavaTestDescription.class,
              RobolectricTestDescription.class,
              GroovyLibraryDescription.class,
              GroovyTestDescription.class,
              KotlinLibraryDescription.class,
              KotlinTestDescription.class,
              PythonLibraryDescription.class,
              PythonTestDescription.class,
              ScalaLibraryDescription.class,
              ScalaTestDescription.class);

  public static boolean isTargetTypeSupported(Class<?> descriptionClass) {
    return SUPPORTED_MODULE_DESCRIPTION_CLASSES.contains(descriptionClass);
  }

  public static boolean areTargetTypesEqual(
      Set<Class<? extends DescriptionWithTargetGraph<?>>> otherTypes) {
    return SUPPORTED_MODULE_DESCRIPTION_CLASSES.equals(otherTypes);
  }

  private final Map<Class<? extends DescriptionWithTargetGraph<?>>, IjModuleRule<?>>
      moduleRuleIndex = new HashMap<>();

  public SupportedTargetTypeRegistry(
      ProjectFilesystem projectFilesystem,
      IjModuleFactoryResolver moduleFactoryResolver,
      IjProjectConfig projectConfig,
      JavaPackageFinder packageFinder) {
    addToIndex(
        new AndroidBinaryModuleRule(projectFilesystem, moduleFactoryResolver, projectConfig));
    addToIndex(
        new AndroidLibraryModuleRule(projectFilesystem, moduleFactoryResolver, projectConfig));
    addToIndex(
        new AndroidResourceModuleRule(projectFilesystem, moduleFactoryResolver, projectConfig));
    addToIndex(new CxxLibraryModuleRule(projectFilesystem, moduleFactoryResolver, projectConfig));
    addToIndex(new CxxTestModuleRule(projectFilesystem, moduleFactoryResolver, projectConfig));
    addToIndex(new JavaBinaryModuleRule(projectFilesystem, moduleFactoryResolver, projectConfig));
    addToIndex(
        new JavaLibraryModuleRule(
            projectFilesystem, moduleFactoryResolver, projectConfig, packageFinder));
    addToIndex(
        new JavaTestModuleRule(
            projectFilesystem, moduleFactoryResolver, projectConfig, packageFinder));
    addToIndex(
        new RobolectricTestModuleRule(projectFilesystem, moduleFactoryResolver, projectConfig));
    addToIndex(
        new GroovyLibraryModuleRule(projectFilesystem, moduleFactoryResolver, projectConfig));
    addToIndex(new GroovyTestModuleRule(projectFilesystem, moduleFactoryResolver, projectConfig));
    addToIndex(
        new KotlinLibraryModuleRule(projectFilesystem, moduleFactoryResolver, projectConfig));
    addToIndex(new KotlinTestModuleRule(projectFilesystem, moduleFactoryResolver, projectConfig));
    addToIndex(
        new PythonLibraryModuleRule(projectFilesystem, moduleFactoryResolver, projectConfig));
    addToIndex(new PythonTestModuleRule(projectFilesystem, moduleFactoryResolver, projectConfig));
    addToIndex(new ScalaLibraryModuleRule(projectFilesystem, moduleFactoryResolver, projectConfig));
    addToIndex(new ScalaTestModuleRule(projectFilesystem, moduleFactoryResolver, projectConfig));
    Preconditions.checkState(areTargetTypesEqual(moduleRuleIndex.keySet()));
  }

  private void addToIndex(IjModuleRule<?> rule) {
    Preconditions.checkArgument(!moduleRuleIndex.containsKey(rule.getDescriptionClass()));
    Preconditions.checkArgument(isTargetTypeSupported(rule.getDescriptionClass()));
    moduleRuleIndex.put(rule.getDescriptionClass(), rule);
  }

  public IjModuleRule<?> getModuleRuleByTargetNodeType(Class<?> targetNodeType) {
    return moduleRuleIndex.get(targetNodeType);
  }
}
