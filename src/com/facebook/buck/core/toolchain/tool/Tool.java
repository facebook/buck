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

package com.facebook.buck.core.toolchain.tool;

import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/** An abstraction for describing some tools used as part of the build. */
public interface Tool extends AddsToRuleKey {

  /** @return the prefix command use to run this tool. */
  ImmutableList<String> getCommandPrefix(SourcePathResolver resolver);

  /**
   * @return the list of environment variables to set when running the command.
   * @param resolver
   */
  ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver);
}
