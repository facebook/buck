/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.rules.macros;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.google.common.collect.ImmutableList;

public interface MacroExpander {

  /**
   * Expand the input given for the this macro to some string.
   */
  String expand(
      BuildTarget target,
      BuildRuleResolver resolver,
      ProjectFilesystem filesystem,
      String input)
      throws MacroException;

  /**
   * @return additional {@link BuildRule}s which provide output which is consumed by the expanded
   *     form of this macro.  These are intended to become dependencies of {@code BuildRule}s that
   *     use this macro.  In many cases, this may just be the {@link BuildRule}s resolved from the
   *     {@link BuildTarget}s returned by {@link #extractParseTimeDeps}.
   */
  ImmutableList<BuildRule> extractAdditionalBuildTimeDeps(
      BuildTarget target,
      BuildRuleResolver resolver,
      String input)
      throws MacroException;

  /**
   * @return names of additional {@link com.facebook.buck.rules.TargetNode}s which must be followed
   *     by the parser to support this macro when constructing the target graph.  To be used by
   *     {@link com.facebook.buck.rules.ImplicitDepsInferringDescription#findDepsForTargetFromConstructorArgs}
   *     to extract implicit dependencies hidden behind macros.
   */
  ImmutableList<BuildTarget> extractParseTimeDeps(BuildTarget target, String input)
      throws MacroException;

}
