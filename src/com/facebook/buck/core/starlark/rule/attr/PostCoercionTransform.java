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

package com.facebook.buck.core.starlark.rule.attr;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.io.filesystem.ProjectFilesystem;

/**
 * Simple interface to take a coerced value for an {@link Attribute} on a build target utilizing
 * information only known at analysis time.
 *
 * <p>This is not done by the type coercer and {@link
 * Attribute#getValue(com.facebook.buck.core.cell.nameresolver.CellNameResolver, ProjectFilesystem,
 * ForwardRelativePath, TargetConfiguration, TargetConfiguration, Object)} for two reasons. The
 * ProjectFilesystem, ForwardRelativePath, TargetConfiguration, Object)} for two reasons. The first
 * is that some information (like {@link ProviderInfoCollection} for dependencies) is not available
 * until the analysis phase, rather than the parsing / coercion / configuration phases. Secondly is
 * for speed. If we do not need to do a transformation on a given attribute, it's better to skip the
 * function call.
 */
@FunctionalInterface
public interface PostCoercionTransform<AdditionalDataType, PostTransformType> {

  /**
   * Transform a coerced attribute value for a specific rule in a way that utilizes deps.
   *
   * <p>This is often done to transform attributes for a build target into something more useful to
   * users in their impelementation functions. e.g. a list of dependencies that are represented as
   * {@link BuildTarget}s into a list of associated {@link ProviderInfoCollection}s
   *
   * @param coercedValue the value that has been coerced by {@link Attribute#getTypeCoercer()}
   * @param additionalData additional data that is used to perform the transformation of the coerced
   *     data.
   * @return The value to now use that utilizes information in {@code deps}
   * @throws IllegalArgumentException if {@code coercedValue} is not of the correct type or could
   *     otherwise not be transformed (e.g. a required dep is missing from {@code deps}).
   */
  PostTransformType postCoercionTransform(Object coercedValue, AdditionalDataType additionalData);
}
