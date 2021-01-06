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

/**
 * Simple interface used to get and set values after they are coerced on a {@link
 * com.facebook.buck.core.starlark.rule.SkylarkDescriptionArg}.
 *
 * <p>This is primarily used to remove a circular dependency in buck around {@link
 * com.facebook.buck.core.starlark.rule.SkylarkDescriptionArg}. We want {@link
 * com.facebook.buck.core.starlark.rule.SkylarkDescriptionArg} to have a copy of {@link
 * com.facebook.buck.core.starlark.rule.SkylarkUserDefinedRule}. We also want {@link
 * com.facebook.buck.core.starlark.rule.SkylarkUserDefinedRule} (which owns the Attribute mapping)
 * to be able to hold onto the corresponding {@link
 * com.facebook.buck.core.starlark.coercer.SkylarkParamInfo} mapping. {@link
 * com.facebook.buck.core.starlark.coercer.SkylarkParamInfo} ultimately needs to be able to set
 * values on a {@link com.facebook.buck.core.starlark.rule.SkylarkDescriptionArg}, though. So, if we
 * used {@link com.facebook.buck.core.starlark.rule.SkylarkDescriptionArg}, rather than a builder,
 * we'd run into this circular dep: {@link
 * com.facebook.buck.core.starlark.rule.SkylarkUserDefinedRule} -> {@link
 * com.facebook.buck.core.starlark.coercer.SkylarkParamInfo} -> {@link
 * com.facebook.buck.core.starlark.rule.SkylarkDescriptionArg} -> {@link
 * com.facebook.buck.core.starlark.rule.SkylarkUserDefinedRule}
 *
 * <p>This abstraction lets us easily just set/get post-coercion values in a {@link
 * com.facebook.buck.core.starlark.rule.SkylarkDescriptionArg} that is in the process of being
 * constructed, without having to know about the {@link
 * com.facebook.buck.core.starlark.rule.SkylarkUserDefinedRule} that the {@link
 * com.facebook.buck.core.starlark.rule.SkylarkDescriptionArgs} holds onto.
 */
public interface SkylarkDescriptionArgBuilder {
  /**
   * Set the value for a specific attribute after it has been successfully coerced/type checked.
   * This attribute must be defined in the originally provided {@link
   * com.facebook.buck.core.starlark.rule.SkylarkUserDefinedRule}
   *
   * @param attr The attribute to set the value for
   * @param value The value to set. Must not be null.
   */
  void setPostCoercionValue(String attr, Object value);

  /**
   * Get an attribute's value after it was set in {@link #setPostCoercionValue(String, Object)}
   *
   * <p>It is an error to get an attribute that has not been set.
   *
   * @param attr The attribute to get the value for
   * @return The value
   */
  Object getPostCoercionValue(String attr);
}
