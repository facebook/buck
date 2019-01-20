/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.skylark.io;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Set;
import org.immutables.value.Value;

/** Set of globs performed while executing the build spec and the results they produce. */
@Value.Immutable(builder = false)
@BuckStyleImmutable
@JsonDeserialize
abstract class AbstractGlobSpecWithResult {
  /** return the GlobSpec for this glob operation. */
  @Value.Parameter
  @JsonProperty("globSpec")
  public abstract GlobSpec getGlobSpec();

  /** return the file paths that the glob operation returned. */
  @Value.Parameter
  @JsonProperty("filePaths")
  public abstract Set<String> getFilePaths();
}
