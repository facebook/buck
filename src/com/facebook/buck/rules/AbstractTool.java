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

package com.facebook.buck.rules;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

/**
 * Provides default implementations of Tool functions. This is only a separate class because Tool
 * cannot depend on some of the things used here.
 */
public interface AbstractTool extends Tool {
  @Override
  default ImmutableCollection<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    return BuildableSupport.deriveDeps(this, ruleFinder).collect(ImmutableList.toImmutableList());
  }
}
