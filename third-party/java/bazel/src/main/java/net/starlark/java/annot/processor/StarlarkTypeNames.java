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
