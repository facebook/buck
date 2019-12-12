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

package com.facebook.buck.core.rules.actions.lib.args;

import com.facebook.buck.core.artifact.ArtifactFilesystem;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import java.util.stream.Stream;

/**
 * Container for a list of objects that can be stringified into command line arguments for an action
 * that executes a program.
 *
 * <p>In the future this will also let us more efficiently concatenate command line arguments that
 * are passed around as providers, as the {@link CommandLineArgs} objects may store the immutable
 * objects more efficiently, and just construct a stream to interate over those internal
 * collections.
 */
public interface CommandLineArgs extends AddsToRuleKey {
  /**
   * @return Get a stream of all raw argument objects that can be stringified with something like
   *     {@link CommandLineArgStringifier#asString(ArtifactFilesystem, boolean, Object)}
   */
  Stream<Object> getArgs();

  /**
   * Get the approximate number of arguments that will be returned for {@link #getArgs()}
   *
   * <p>This can be handy to pre-size destination collections
   *
   * @return the approximate number of arguments. If retrieving the accurate count is efficient, a
   *     correct number is preferred. However if getting a correct number is impossible or
   *     expensive, an approximation is acceptable.
   */
  int getEstimatedArgsCount();
}
