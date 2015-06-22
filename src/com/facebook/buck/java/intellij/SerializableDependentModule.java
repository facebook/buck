/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.java.intellij;

import com.facebook.buck.model.BuildTarget;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

import javax.annotation.Nullable;

@JsonInclude(Include.NON_NULL)
class SerializableDependentModule {

  @JsonProperty
  protected final String type;
  @JsonProperty
  @Nullable
  protected String name;
  @JsonProperty
  @Nullable
  protected String moduleName;
  @JsonProperty
  @Nullable
  protected Boolean forTests;
  /**
   * Set if {@link #type} is {@code jdk}.
   */
  @JsonProperty
  @Nullable
  protected String jdkName;
  /**
   * Set if {@link #type} is {@code jdk}.
   */
  @JsonProperty
  @Nullable
  protected String jdkType;
  @JsonProperty
  @Nullable
  String scope;

  private static final String LIBRARY_DEPENDENCY_TYPE = "library";
  private static final String MODULE_DEPENDENCY_TYPE = "module";

  // The BuildTarget which this DependentModule represent. For example
  // given the target:
  // java_library(name = "bar", deps = [ "//third_party/guava" ])
  // The DependentModule's target would be ":bar"
  @Nullable
  private final BuildTarget target;

  private SerializableDependentModule(String type, @Nullable BuildTarget target) {
    this.type = type;
    this.target = target;
  }

  boolean isLibrary() {
    return LIBRARY_DEPENDENCY_TYPE.equals(type);
  }

  boolean isModule() {
    return MODULE_DEPENDENCY_TYPE.equals(type);
  }

  String getLibraryName() {
    Preconditions.checkState(isLibrary());
    return Preconditions.checkNotNull(name);
  }

  static SerializableDependentModule newLibrary(
      @Nullable BuildTarget owningTarget,
      String libraryName) {
    SerializableDependentModule module =
        new SerializableDependentModule(LIBRARY_DEPENDENCY_TYPE, owningTarget);
    module.name = libraryName;
    return module;
  }

  static SerializableDependentModule newModule(BuildTarget owningTarget, String moduleName) {
    SerializableDependentModule module =
        new SerializableDependentModule(MODULE_DEPENDENCY_TYPE, owningTarget);
    module.moduleName = moduleName;
    return module;
  }

  static SerializableDependentModule newSourceFolder() {
    return new SerializableDependentModule("sourceFolder", null);
  }

  static SerializableDependentModule newInheritedJdk() {
    return new SerializableDependentModule("inheritedJdk", null);
  }

  static SerializableDependentModule newStandardJdk(String jdkName, String jdkType) {
    SerializableDependentModule dependentModule = new SerializableDependentModule("jdk", null);
    dependentModule.jdkName = jdkName;
    dependentModule.jdkType = jdkType;
    return dependentModule;
  }

  static SerializableDependentModule newIntelliJPluginJdk() {
    SerializableDependentModule dependentModule = new SerializableDependentModule("jdk", null);
    // TODO(mbolin): Find the appropriate jdkName for the user's machine.
    // "IDEA IC-117.798" is the one used on my machine for IntelliJ Community Edition 11.1.3.
    // It seems as though we need to find the IntelliJ executable on the user's machine and ask
    // what version it is, which is probably a path to madness. Instead, a user should probably
    // define a ~/.buckconfig or something that contains this information.
    dependentModule.jdkName = "IDEA IC-117.798";
    dependentModule.jdkType = "IDEA JDK";
    return dependentModule;
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof SerializableDependentModule)) {
      return false;
    }
    SerializableDependentModule that = (SerializableDependentModule) obj;
    return Objects.equal(this.target, that.target) &&
        Objects.equal(this.type, that.type) &&
        Objects.equal(this.scope, that.scope) &&
        Objects.equal(this.name, that.name) &&
        Objects.equal(this.moduleName, that.moduleName) &&
        Objects.equal(this.forTests, that.forTests) &&
        Objects.equal(this.jdkName, that.jdkName) &&
        Objects.equal(this.jdkType, that.jdkType);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(target, type, scope, name, moduleName, forTests, jdkName, jdkType);
  }

  // This is helpful in the event of a unit test failure.
  @Override
  public String toString() {
    return MoreObjects.toStringHelper(SerializableDependentModule.class)
        .add("target", target)
        .add("type", type)
        .add("scope", scope)
        .add("name", name)
        .add("moduleName", moduleName)
        .add("forTests", forTests)
        .add("jdkName", jdkName)
        .add("jdkType", jdkType)
        .toString();
  }
}
