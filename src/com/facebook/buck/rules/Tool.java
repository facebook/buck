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
 * An abstraction for describing some tools used as part of the build.
 */
public interface Tool extends RuleKeyAppendable {

  /**
   * @return all {@link BuildRule}s this tool requires to run.
   * @param resolver Used to resolve any build rules from {@link SourcePath}s.
   */
  ImmutableCollection<BuildRule> getDeps(SourcePathResolver resolver);

  /**
   * @return all {@link SourcePath}s this tool requires to run.
   */
  ImmutableCollection<SourcePath> getInputs();

  /**
   * @return the prefix command use to run this tool.
   */
  ImmutableList<String> getCommandPrefix(SourcePathResolver resolver);

}
