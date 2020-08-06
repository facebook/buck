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

package com.facebook.buck.core.starlark.coercer;

import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.rules.coercer.AbstractParamInfo;
import com.google.common.base.Preconditions;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Represents a single field that can be represented in build files, backed by an {@link Attribute}.
 * This is used to get/set user specified attributes on {@link
 * com.facebook.buck.core.starlark.rule.SkylarkDescriptionArg}
 */
public class SkylarkParamInfo<T> extends AbstractParamInfo<T> {

  private final Attribute<T> attr;

  /**
   * Create an instance of {@link SkylarkParamInfo}
   *
   * @param name the user facing name of this attribute
   * @param attr the attribute used to get coercion information, constraints, etc for this param
   */
  public SkylarkParamInfo(String name, Attribute<T> attr) {
    super(name, attr.getTypeCoercer());
    this.attr = attr;
  }

  public Attribute<?> getAttr() {
    return attr;
  }

  @Override
  public boolean isOptional() {
    return attr.getTypeCoercer().getOutputType().getRawType().isAssignableFrom(Optional.class);
  }

  @Nullable
  @Override
  public Hint getHint() {
    return null;
  }

  @Nullable
  @Override
  public Object getImplicitPreCoercionValue() {
    return attr.getPreCoercionDefaultValue();
  }

  @Override
  @SuppressWarnings("unchecked")
  public T get(Object dto) {
    Preconditions.checkArgument(dto instanceof SkylarkDescriptionArgBuilder);
    return (T) ((SkylarkDescriptionArgBuilder) dto).getPostCoercionValue(getName());
  }

  @Override
  public void setCoercedValue(Object dto, Object value) {
    Preconditions.checkArgument(dto instanceof SkylarkDescriptionArgBuilder);
    ((SkylarkDescriptionArgBuilder) dto).setPostCoercionValue(getName(), value);
  }
}
