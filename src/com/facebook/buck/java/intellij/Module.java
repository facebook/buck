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

package com.facebook.buck.java.intellij;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.google.common.annotations.VisibleForTesting;

import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

@JsonInclude(Include.NON_NULL)
@VisibleForTesting
final class Module extends SerializableModule {

  static final Comparator<Module> BUILDTARGET_NAME_COMARATOR = new Comparator<Module>() {
    @Override
    public int compare(Module a, Module b) {
        return a.target.getFullyQualifiedName().compareTo(b.target.getFullyQualifiedName());
    }
  };

  // In IntelliJ, options in an .iml file that correspond to file paths should be relative to the
  // location of the .iml file itself.
  final BuildRule srcRule;
  final BuildTarget target;  // For error reporting

  Module(BuildRule srcRule, BuildTarget target) {
    this.srcRule = srcRule;
    this.target = target;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  @Nullable public List<DependentModule> getDependencies() {
    return (List) dependencies;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public void setModuleDependencies(List<DependentModule> moduleDependencies) {
    this.dependencies = (List) moduleDependencies;
  }
}
