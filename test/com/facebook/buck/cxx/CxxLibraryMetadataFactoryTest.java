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

package com.facebook.buck.cxx;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.junit.MatcherAssert.assertThat;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import org.junit.Test;

public class CxxLibraryMetadataFactoryTest {

  @Test
  public void asRawHeaders() {
    assertThat(
        CxxLibraryMetadataFactory.asRawHeaders(
            new FakeProjectFilesystem(),
            new TestActionGraphBuilder(),
            ImmutableMap.of(Paths.get("bar.h"), FakeSourcePath.of("foo/bar.h"))),
        equalTo(
            Either.ofRight(
                new CxxLibraryMetadataFactory.AsRawHeadersResult(
                    ImmutableSortedSet.of(FakeSourcePath.of("foo/bar.h")),
                    ImmutableSortedSet.of(FakeSourcePath.of("foo"))))));
    assertThat(
        CxxLibraryMetadataFactory.asRawHeaders(
            new FakeProjectFilesystem(),
            new TestActionGraphBuilder(),
            ImmutableMap.of(Paths.get("foo/bar.h"), FakeSourcePath.of("foo/bar.h"))),
        equalTo(
            Either.ofRight(
                new CxxLibraryMetadataFactory.AsRawHeadersResult(
                    ImmutableSortedSet.of(FakeSourcePath.of("foo/bar.h")),
                    ImmutableSortedSet.of(FakeSourcePath.of(""))))));
  }

  @Test
  public void asRawHeadersFailedForRemappedHeaders() {
    assertThat(
        CxxLibraryMetadataFactory.asRawHeaders(
            new FakeProjectFilesystem(),
            new TestActionGraphBuilder(),
            ImmutableMap.of(Paths.get("foo/test.h"), FakeSourcePath.of("foo/bar.h"))),
        equalTo(
            Either.ofLeft(
                new CxxLibraryMetadataFactory.AsRawHeadersError(
                    RelPath.of(Paths.get("foo/test.h")), FakeSourcePath.of("foo/bar.h")))));
  }

  @Test
  public void asRawHeadersFailedForGeneratedHeaders() {
    SourcePath path = DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//:gen"));
    assertThat(
        CxxLibraryMetadataFactory.asRawHeaders(
            new FakeProjectFilesystem(),
            new TestActionGraphBuilder(),
            ImmutableMap.of(Paths.get("foo/bar.h"), path)),
        equalTo(
            Either.ofLeft(
                new CxxLibraryMetadataFactory.AsRawHeadersError(
                    RelPath.of(Paths.get("foo/bar.h")), path))));
  }
}
