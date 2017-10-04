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

import com.facebook.buck.jvm.java.abi.source.api.SourceOnlyAbiRuleInfo;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

class DefaultSourceOnlyAbiRuleInfo implements SourceOnlyAbiRuleInfo {
  private final BuildTarget buildTarget;
  private final boolean ruleIsRequiredForSourceOnlyAbi;
  private final Map<String, Set<String>> packagesContents = new HashMap<>();

  @Nullable private JavaFileManager fileManager;

  public DefaultSourceOnlyAbiRuleInfo(
      BuildTarget buildTarget, boolean ruleIsRequiredForSourceOnlyAbi) {
    this.buildTarget = buildTarget;
    this.ruleIsRequiredForSourceOnlyAbi = ruleIsRequiredForSourceOnlyAbi;
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
  public boolean classIsOnBootClasspath(String binaryName) {
    String packageName = getPackageName(binaryName);
    Set<String> packageContents = getPackageContents(packageName);
    return packageContents.contains(binaryName);
  }

  private Set<String> getPackageContents(String packageName) {
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
