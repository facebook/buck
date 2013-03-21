/*
 * Copyright 2012-present Facebook, Inc.
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

import java.io.File;

public interface ArtifactCache {
  /**
   * Fetch a cached artifact, keyed by ruleKey, save the artifact to path specified by output, and
   * return true on success.
   *
   * @param ruleKey cache fetch key
   * @param output path to store artifact to
   * @return true on fetch success, false otherwise.
   */
  public boolean fetch(RuleKey ruleKey, File output);

  /**
   * Store the artifact at path specified by output to cache, such that it can later be fetched
   * using ruleKey as the lookup key.  If any internal errors occur, fail silently and continue
   * execution.
   *
   * @param ruleKey cache store key
   * @param output path to read artifact from
   */
  public void store(RuleKey ruleKey, File output);
}
