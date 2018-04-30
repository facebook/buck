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

package com.facebook.buck.apple;

import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.swift.SwiftDescriptions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AppleLibrarySwiftMetadata {
  private ImmutableSet<SourceWithFlags> swiftSources;
  private ImmutableSet<SourceWithFlags> nonSwiftSources;

  public AppleLibrarySwiftMetadata(
      ImmutableSet<SourceWithFlags> swiftSources, ImmutableSet<SourceWithFlags> nonSwiftSources) {
    this.swiftSources = swiftSources;
    this.nonSwiftSources = nonSwiftSources;
  }

  public ImmutableSet<SourceWithFlags> getSwiftSources() {
    return swiftSources;
  }

  public ImmutableSet<SourceWithFlags> getNonSwiftSources() {
    return nonSwiftSources;
  }

  public static AppleLibrarySwiftMetadata from(
      ImmutableSortedSet<SourceWithFlags> allSources, SourcePathResolver pathResolver) {
    Map<Boolean, List<SourceWithFlags>> swiftAndNonSwiftSources =
        allSources
            .stream()
            .collect(
                Collectors.partitioningBy(
                    src -> SwiftDescriptions.isSwiftSource(src, pathResolver)));

    ImmutableSet<SourceWithFlags> swiftSources =
        swiftAndNonSwiftSources
            .getOrDefault(true, Collections.emptyList())
            .stream()
            .collect(ImmutableSet.toImmutableSet());

    ImmutableSet<SourceWithFlags> nonSwiftSources =
        swiftAndNonSwiftSources
            .getOrDefault(false, Collections.emptyList())
            .stream()
            .collect(ImmutableSet.toImmutableSet());

    return new AppleLibrarySwiftMetadata(swiftSources, nonSwiftSources);
  }
}
