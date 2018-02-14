/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.java.abi.source.api.SourceOnlyAbiRuleInfo;
import com.facebook.buck.jvm.java.lang.model.MoreElements;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

class DefaultSourceOnlyAbiRuleInfo implements SourceOnlyAbiRuleInfo {
  private final ImmutableList<HasJavaAbi> fullJarClasspathRules;
  private final ImmutableList<HasJavaAbi> abiJarClasspathRules;
  private final ImmutableSet<String> targetsRequiredForSourceOnlyAbi;
  private final BuildTarget buildTarget;
  private final boolean ruleIsRequiredForSourceOnlyAbi;
  private final Map<String, Set<String>> packagesContents = new HashMap<>();

  @Nullable private JavaFileManager fileManager;

  public DefaultSourceOnlyAbiRuleInfo(
      SourcePathRuleFinder ruleFinder,
      BuildTarget buildTarget,
      boolean ruleIsRequiredForSourceOnlyAbi,
      DefaultJavaLibraryClasspaths classpaths,
      DefaultJavaLibraryClasspaths sourceOnlyAbiClasspaths) {
    this.buildTarget = buildTarget;
    this.ruleIsRequiredForSourceOnlyAbi = ruleIsRequiredForSourceOnlyAbi;
    fullJarClasspathRules =
        classpaths
            .getCompileTimeClasspathSourcePaths()
            .stream()
            .map(ruleFinder::getRule)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .filter(rule -> rule instanceof HasJavaAbi)
            .map(rule -> (HasJavaAbi) rule)
            .collect(ImmutableList.toImmutableList());

    targetsRequiredForSourceOnlyAbi =
        sourceOnlyAbiClasspaths
            .getCompileTimeClasspathSourcePaths()
            .stream()
            .map(ruleFinder::getRule)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(BuildRule::getBuildTarget)
            .map(BuildTarget::getUnflavoredBuildTarget)
            .map(Object::toString)
            .collect(ImmutableSet.toImmutableSet());

    abiJarClasspathRules =
        fullJarClasspathRules
            .stream()
            .filter(
                rule ->
                    targetsRequiredForSourceOnlyAbi.contains(
                        rule.getBuildTarget().getUnflavoredBuildTarget().toString()))
            .collect(ImmutableList.toImmutableList());
  }

  @Override
  public void setFileManager(JavaFileManager fileManager) {
    this.fileManager = fileManager;
  }

  private JavaFileManager getFileManager() {
    return Preconditions.checkNotNull(fileManager);
  }

  @Override
  public String getRuleName() {
    return buildTarget.toString();
  }

  @Override
  public boolean ruleIsRequiredForSourceOnlyAbi() {
    return ruleIsRequiredForSourceOnlyAbi;
  }

  @Override
  @Nullable
  public String getOwningTarget(Elements elements, Element element) {
    return getOwningTarget(elements, element, this.fullJarClasspathRules);
  }

  @Nullable
  private String getOwningTargetIfAvailableForSourceOnlyAbi(Elements elements, Element element) {
    return getOwningTarget(elements, element, this.abiJarClasspathRules);
  }

  @Nullable
  private String getOwningTarget(
      Elements elements, Element element, ImmutableList<HasJavaAbi> classpath) {
    TypeElement enclosingType = MoreElements.getTypeElement(element);
    String classFilePath =
        elements.getBinaryName(enclosingType).toString().replace('.', File.separatorChar)
            + ".class";

    for (HasJavaAbi classpathRule : classpath) {
      if (classpathRule.jarContains(classFilePath)) {
        return classpathRule.getBuildTarget().getUnflavoredBuildTarget().toString();
      }
    }

    return null;
  }

  @Override
  public boolean elementIsAvailableForSourceOnlyAbi(Elements elements, Element element) {
    return getOwningTargetIfAvailableForSourceOnlyAbi(elements, element) != null
        || classIsOnBootClasspath(elements, element);
  }

  private boolean classIsOnBootClasspath(Elements elements, Element element) {
    String binaryName = elements.getBinaryName(MoreElements.getTypeElement(element)).toString();
    String packageName = getPackageName(binaryName);
    Set<String> packageContents = getBootclasspathPackageContents(packageName);
    return packageContents.contains(binaryName);
  }

  private Set<String> getBootclasspathPackageContents(String packageName) {
    Set<String> packageContents = packagesContents.get(packageName);
    if (packageContents == null) {
      packageContents = new HashSet<>();

      try {
        JavaFileManager fileManager = getFileManager();
        for (JavaFileObject javaFileObject :
            fileManager.list(
                StandardLocation.PLATFORM_CLASS_PATH,
                packageName,
                EnumSet.of(JavaFileObject.Kind.CLASS),
                true)) {
          packageContents.add(
              fileManager.inferBinaryName(StandardLocation.PLATFORM_CLASS_PATH, javaFileObject));
        }
      } catch (IOException e) {
        throw new HumanReadableException(e, "Failed to list boot classpath contents.");
        // Do nothing
      }
      packagesContents.put(packageName, packageContents);
    }
    return packageContents;
  }

  private String getPackageName(String binaryName) {
    int lastDot = binaryName.lastIndexOf('.');
    if (lastDot < 0) {
      return "";
    }

    return binaryName.substring(0, lastDot);
  }
}
