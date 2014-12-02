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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.collect.ImmutableList;

/**
 * Represents tool used as part of the C/C++ build process.
 */
public interface Tool extends RuleKeyAppendable {

  /**
   * @return all `BuildRule`s this tools requires to be built before it can be used.
   */
  ImmutableList<BuildRule> getBuildRules(SourcePathResolver resolver);

  /**
   * @return the prefix command use to run this tool.
   */
  ImmutableList<String> getCommandPrefix(SourcePathResolver resolver);

}
