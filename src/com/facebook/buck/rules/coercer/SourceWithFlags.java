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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;

import org.immutables.value.Value;

import java.util.List;

/**
 * Simple type representing a {@link SourcePath} and a list of file-specific flags.
 */
@Value.Immutable
@BuckStyleImmutable
public abstract class SourceWithFlags {

  @Value.Parameter
  public abstract SourcePath getSourcePath();

  @Value.Parameter
  public abstract List<String> getFlags();

  public static SourceWithFlags of(SourcePath sourcePath) {
    return ImmutableSourceWithFlags.of(sourcePath, ImmutableList.<String>of());
  }

  public static SourceWithFlags of(SourcePath sourcePath, List<String> flags) {
    return ImmutableSourceWithFlags.of(sourcePath, ImmutableList.copyOf(flags));
  }

}
