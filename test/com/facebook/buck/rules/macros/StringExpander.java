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

package com.facebook.buck.rules.macros;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.google.common.collect.ImmutableList;

class StringExpander implements MacroExpander {

  private final String toReturn;

  public StringExpander(String toReturn) {
    this.toReturn = toReturn;
  }

  @Override
  public String expand(
      BuildTarget target,
      BuildRuleResolver resolver,
      ProjectFilesystem filesystem,
      String input) throws MacroException {
    return toReturn;
  }

  @Override
  public ImmutableList<BuildTarget> extractTargets(
      BuildTarget target,
      String input) throws MacroException {
    return ImmutableList.of();
  }
}
