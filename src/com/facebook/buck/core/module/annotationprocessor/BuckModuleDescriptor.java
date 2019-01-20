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

package com.facebook.buck.core.module.annotationprocessor;

import java.util.List;
import javax.lang.model.element.TypeElement;

/** Contains information about Buck module and its location */
class BuckModuleDescriptor {

  public final TypeElement buckModuleAnnotation;
  public final String packageName;
  public final String className;
  public final String name;
  public final List<String> dependencies;

  public BuckModuleDescriptor(
      TypeElement buckModuleAnnotation,
      String packageName,
      String className,
      String name,
      List<String> dependencies) {
    this.buckModuleAnnotation = buckModuleAnnotation;
    this.packageName = packageName;
    this.className = className;
    this.name = name;
    this.dependencies = dependencies;
  }

  @Override
  public String toString() {
    return name;
  }
}
