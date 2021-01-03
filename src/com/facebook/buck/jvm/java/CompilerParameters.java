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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.jvm.java.abi.source.api.SourceOnlyAbiRuleInfoFactory;
import com.google.common.collect.ImmutableSortedSet;
import javax.annotation.Nullable;
import org.immutables.value.Value;

@BuckStyleValueWithBuilder
public abstract class CompilerParameters {
  @Value.Default
  public ImmutableSortedSet<RelPath> getSourceFilePaths() {
    return ImmutableSortedSet.of();
  }

  @Value.Default
  public ImmutableSortedSet<RelPath> getClasspathEntries() {
    return ImmutableSortedSet.of();
  }

  public abstract CompilerOutputPaths getOutputPaths();

  @Value.Default
  public AbiGenerationMode getAbiGenerationMode() {
    return AbiGenerationMode.CLASS;
  }

  @Value.Default
  public AbiGenerationMode getAbiCompatibilityMode() {
    return getAbiGenerationMode();
  }

  @Value.Default
  public boolean shouldTrackClassUsage() {
    return false;
  }

  @Value.Default
  public boolean shouldTrackJavacPhaseEvents() {
    return false;
  }

  @Nullable
  public abstract SourceOnlyAbiRuleInfoFactory getSourceOnlyAbiRuleInfoFactory();

  public static ImmutableCompilerParameters.Builder builder() {
    return ImmutableCompilerParameters.builder();
  }
}
