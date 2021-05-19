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

package net.starlark.java.annot.processor;

import javax.lang.model.type.TypeMirror;

/** Precomputed type names used in annotation processor. */
class StarlarkTypeNames {

  final TypeMirror objectType;
  final TypeMirror stringType;
  final TypeMirror integerType;
  final TypeMirror booleanType;
  final TypeMirror listType;
  final TypeMirror mapType;
  final TypeMirror starlarkValueType;
  final TypeMirror stringModuleType;
  final TypeMirror methodLibraryType;

  public StarlarkTypeNames(
      TypeMirror objectType,
      TypeMirror stringType,
      TypeMirror integerType,
      TypeMirror booleanType,
      TypeMirror listType,
      TypeMirror mapType,
      TypeMirror starlarkValueType,
      TypeMirror stringModuleType,
      TypeMirror methodLibraryType) {
    this.objectType = objectType;
    this.stringType = stringType;
    this.integerType = integerType;
    this.booleanType = booleanType;
    this.listType = listType;
    this.mapType = mapType;
    this.starlarkValueType = starlarkValueType;
    this.stringModuleType = stringModuleType;
    this.methodLibraryType = methodLibraryType;
  }
}
