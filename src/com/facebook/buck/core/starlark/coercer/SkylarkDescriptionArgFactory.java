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

import com.facebook.buck.core.description.arg.DataTransferObject;
import com.facebook.buck.rules.coercer.DataTransferObjectDescriptor;
import com.facebook.buck.rules.coercer.ParamsInfo;

/**
 * Shim interface to get around circular dependencies between {@link
 * com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory} and {@link
 * com.facebook.buck.core.starlark.rule.SkylarkDescriptionArg}
 */
public interface SkylarkDescriptionArgFactory {

  /**
   * Create an instance of {@link com.facebook.buck.core.starlark.rule.SkylarkDescriptionArg} using
   * the rule from this object. Note that {@link T} is only used because we can't actually use
   * {@link com.facebook.buck.core.starlark.rule.SkylarkDescriptionArg}
   */
  <T extends DataTransferObject> DataTransferObjectDescriptor<T> getConstructorArgDescriptor(
      Class<T> dtoClass);

  /** Get all of the parameter information */
  ParamsInfo getAllParamInfo();
}
