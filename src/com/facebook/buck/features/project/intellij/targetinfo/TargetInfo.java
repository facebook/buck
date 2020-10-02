/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.project.intellij.targetinfo;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Objects;

/** Represents serialized target info. */
public class TargetInfo {
  /** Type of buck rule. */
  public static enum BuckType {
    android_binary,
    android_build_config,
    android_library,
    android_prebuilt_aar,
    android_resource,
    cxx_library,
    cxx_test,
    java_binary,
    java_library,
    java_test,
    kotlin_library,
    kotlin_test,
    prebuilt_jar,
    python_library,
    python_test,
    robolectric_test
  }

  /** IntelliJ Module type. */
  public static enum IntelliJType {
    library,
    module,
    module_library
  }

  /** Module language. */
  public static enum ModuleLanguage {
    JAVA,
    KOTLIN,
    SCALA
  }

  @SerializedName("buck.type")
  public BuckType ruleType;

  @SerializedName("intellij.type")
  public IntelliJType intellijType;

  @SerializedName("intellij.name")
  public String intellijName;

  @SerializedName("intellij.file_path")
  public String intellijFilePath;

  @SerializedName("module.lang")
  public ModuleLanguage moduleLanguage;

  @SerializedName("generated_sources")
  public List<String> generatedSources;

  public BuckType getRuleType() {
    return ruleType;
  }

  public IntelliJType getIntelliJType() {
    return intellijType;
  }

  public String getIntelliJName() {
    return intellijName;
  }

  public String getIntelliJFilePath() {
    return intellijFilePath;
  }

  public boolean isLibrary() {
    return getIntelliJType() == IntelliJType.library
        || getIntelliJType() == IntelliJType.module_library;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        ruleType, intellijType, intellijName, intellijFilePath, moduleLanguage, generatedSources);
  }

  @Override
  public boolean equals(Object o) {
    if (o == null) {
      return false;
    }
    if (!(o instanceof TargetInfo)) {
      return false;
    }
    TargetInfo other = (TargetInfo) o;
    return Objects.equals(ruleType, other.ruleType)
        && Objects.equals(intellijType, other.intellijType)
        && Objects.equals(intellijName, other.intellijName)
        && Objects.equals(intellijFilePath, other.intellijFilePath)
        && Objects.equals(moduleLanguage, other.moduleLanguage)
        && Objects.equals(generatedSources, other.generatedSources);
  }
}
