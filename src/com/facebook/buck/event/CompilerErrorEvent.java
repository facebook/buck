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

package com.facebook.buck.event;

import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.event.external.events.CompilerErrorEventExternalInterface;
import com.google.common.collect.ImmutableSet;

/** Compiler Error Event that used in ideabuck plugin. */
@BuckStyleValueWithBuilder
public abstract class CompilerErrorEvent implements CompilerErrorEventExternalInterface {

  public enum CompilerType {
    JAVA("java"),
    UNKNOWN("unknown");

    private final String name;

    CompilerType(String name) {
      this.name = name;
    }

    /** Returns {@link CompilerType} */
    public static CompilerType determineCompilerType(String compilerName) {
      String lowerCasedCompilerName = compilerName.toLowerCase();
      for (CompilerType compilerType : values()) {
        if (lowerCasedCompilerName.contains(compilerType.name)) {
          return compilerType;
        }
      }
      return UNKNOWN;
    }
  }

  @Override
  public abstract long getTimestampMillis();

  @Override
  public String getEventName() {
    return COMPILER_ERROR_EVENT;
  }

  @Override
  public abstract String getError();

  @Override
  public abstract String getTarget();

  public abstract CompilerType getCompilerType();

  @Override
  public abstract ImmutableSet<String> getSuggestions();

  public static ImmutableCompilerErrorEvent.Builder builder() {
    return ImmutableCompilerErrorEvent.builder();
  }
}
