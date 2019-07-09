/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.base.CaseFormat;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Represents a single field that can be represented in buck build files. This base class implements
 * some common logic that is used by both all subclasses
 */
public abstract class AbstractParamInfo implements ParamInfo {

  private final String name;
  private final TypeCoercer<?> typeCoercer;

  /** Create an instance of {@link AbstractParamInfo} */
  public AbstractParamInfo(String name, TypeCoercer<?> typeCoercer) {
    this.name = name;
    this.typeCoercer = typeCoercer;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public TypeCoercer<?> getTypeCoercer() {
    return typeCoercer;
  }

  @Override
  public String getPythonName() {
    return CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, getName());
  }

  @Override
  public boolean isDep() {
    Hint hint = getHint();
    if (hint != null) {
      return hint.isDep();
    }
    return Hint.DEFAULT_IS_DEP;
  }

  @Override
  public boolean isTargetGraphOnlyDep() {
    Hint hint = getHint();
    if (hint != null && hint.isTargetGraphOnlyDep()) {
      Preconditions.checkState(hint.isDep(), "Conditional deps are only applicable for deps.");
      return true;
    }
    return Hint.DEFAULT_IS_TARGET_GRAPH_ONLY_DEP;
  }

  @Override
  public boolean isInput() {
    Hint hint = getHint();
    if (hint != null) {
      return hint.isInput();
    }
    return Hint.DEFAULT_IS_INPUT;
  }

  @Override
  public boolean isConfigurable() {
    Hint hint = getHint();
    if (hint != null) {
      return hint.isConfigurable();
    }
    return Hint.DEFAULT_IS_CONFIGURABLE;
  }

  @Override
  public boolean splitConfiguration() {
    Hint hint = getHint();
    if (hint != null) {
      return hint.splitConfiguration();
    }
    return Hint.DEFAULT_SPLIT_CONFIGURATION;
  }

  @Override
  public Class<?> getResultClass() {
    return typeCoercer.getOutputClass();
  }

  @Override
  public void traverse(CellPathResolver cellPathResolver, Traversal traversal, Object dto) {
    traverseHelper(cellPathResolver, typeCoercer, traversal, dto);
  }

  @SuppressWarnings("unchecked")
  private <U> void traverseHelper(
      CellPathResolver cellPathResolver,
      TypeCoercer<U> typeCoercer,
      Traversal traversal,
      Object dto) {
    U object = (U) get(dto);
    if (object != null) {
      typeCoercer.traverse(cellPathResolver, object, traversal);
    }
  }

  @Override
  public void setFromParams(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      BuildTarget buildTarget,
      TargetConfiguration targetConfiguration,
      Object arg,
      Map<String, ?> instance)
      throws ParamInfoException {
    set(
        cellRoots,
        filesystem,
        buildTarget.getBasePath(),
        targetConfiguration,
        arg,
        instance.get(name));
  }

  @Override
  public void set(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      Object dto,
      @Nullable Object value)
      throws ParamInfoException {
    if (value == null) {
      return;
    }
    try {
      setCoercedValue(
          dto,
          typeCoercer.coerce(
              cellRoots, filesystem, pathRelativeToProjectRoot, targetConfiguration, value));
    } catch (CoerceFailedException e) {
      throw new ParamInfoException(name, e.getMessage(), e);
    }
  }

  @Override
  public boolean hasElementTypes(Class<?>... types) {
    return typeCoercer.hasElementClass(types);
  }

  /** Only valid when comparing {@link AbstractParamInfo} instances from the same description. */
  @Override
  public int compareTo(ParamInfo that) {
    if (this == that) {
      return 0;
    }

    return this.getName().compareTo(that.getName());
  }

  @Override
  public int hashCode() {
    return name.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof AbstractParamInfo)) {
      return false;
    }

    ParamInfo that = (ParamInfo) obj;
    return name.equals(that.getName());
  }
}
