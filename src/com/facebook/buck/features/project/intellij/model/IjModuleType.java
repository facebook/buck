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

package com.facebook.buck.features.project.intellij.model;

import java.util.Optional;

/**
 * List of module types that are recognized by the model.
 *
 * <p>The types in this enum are ordered by priority. When multiple targets apply the type on a
 * module only the type with the highest priority is used as the result type.
 *
 * <p>For example, if one target's rule sets the type to ANDROID_MODULE and another target's rule
 * sets it to JAVA_MODULE, the final module type would be ANDROID_MODULE.
 */
public enum IjModuleType {

  /**
   * Modules that contain IntelliJ plugins use this custom type to indicate that they should be run
   * in an environment with an IDEA installation.
   */
  INTELLIJ_PLUGIN_MODULE("PLUGIN_MODULE") {
    @Override
    public Optional<String> getSdkName(IjProjectConfig projectConfig) {
      return projectConfig.getIntellijModuleSdkName();
    }

    @Override
    public String getSdkType(IjProjectConfig projectConfig) {
      return SDK_TYPE_IDEA;
    }
  },

  /**
   * Similar to {@link #ANDROID_MODULE} but can contain Android resources and thus cannot be
   * aggregated with a module located in the parent directory.
   */
  ANDROID_RESOURCES_MODULE("JAVA_MODULE") {
    @Override
    public Optional<String> getSdkName(IjProjectConfig projectConfig) {
      return projectConfig.getAndroidModuleSdkName();
    }

    @Override
    public String getSdkType(IjProjectConfig projectConfig) {
      return projectConfig.getAndroidModuleSdkType().orElse(SDK_TYPE_ANDROID);
    }

    @Override
    public boolean canBeAggregated(IjProjectConfig projectConfig) {
      return projectConfig.isAggregatingAndroidResourceModulesEnabled();
    }

    @Override
    public int getAggregationLimit(IjProjectConfig projectConfig) {
      return projectConfig.getAggregationLimitForAndroidResourceModule();
    }
  },

  /**
   * A module with code that does not contain Android resources.
   *
   * @see #ANDROID_RESOURCES_MODULE
   */
  ANDROID_MODULE("JAVA_MODULE") {
    @Override
    public Optional<String> getSdkName(IjProjectConfig projectConfig) {
      return projectConfig.getAndroidModuleSdkName();
    }

    @Override
    public String getSdkType(IjProjectConfig projectConfig) {
      return projectConfig.getAndroidModuleSdkType().orElse(SDK_TYPE_ANDROID);
    }
  },

  JAVA_MODULE("JAVA_MODULE") {
    @Override
    public Optional<String> getSdkName(IjProjectConfig projectConfig) {
      return projectConfig.getJavaModuleSdkName();
    }

    @Override
    public String getSdkType(IjProjectConfig projectConfig) {
      return projectConfig.getJavaModuleSdkType().orElse(SDK_TYPE_JAVA);
    }
  },

  UNKNOWN_MODULE("JAVA_MODULE") {
    @Override
    public Optional<String> getSdkName(IjProjectConfig projectConfig) {
      return Optional.empty();
    }

    @Override
    public String getSdkType(IjProjectConfig projectConfig) {
      return SDK_TYPE_JAVA;
    }
  },
  ;

  // From constructor of com.intellij.openapi.projectRoots.impl.JavaSdkImpl
  private static final String SDK_TYPE_JAVA = "JavaSDK";

  // From constructor of org.jetbrains.android.sdk.AndroidSdkType
  private static final String SDK_TYPE_ANDROID = "Android SDK";

  // From constructor of org.jetbrains.idea.devkit.projectRoots.IdeaJdk
  private static final String SDK_TYPE_IDEA = "IDEA JDK";

  IjModuleType(String imlModuleType) {
    this.imlModuleType = imlModuleType;
  }

  private final String imlModuleType;

  public abstract Optional<String> getSdkName(IjProjectConfig projectConfig);

  public abstract String getSdkType(IjProjectConfig projectConfig);

  @SuppressWarnings("unused")
  public boolean canBeAggregated(IjProjectConfig projectConfig) {
    return true;
  }

  public String getImlModuleType() {
    return imlModuleType;
  }

  public boolean hasHigherPriorityThan(IjModuleType moduleType) {
    return this.compareTo(moduleType) < 0;
  }

  @SuppressWarnings("unused")
  public int getAggregationLimit(IjProjectConfig projectConfig) {
    return Integer.MAX_VALUE;
  }
}
