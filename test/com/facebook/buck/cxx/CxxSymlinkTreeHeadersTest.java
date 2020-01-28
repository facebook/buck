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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.modern.SerializationTestHelper;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableSortedMap;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class CxxSymlinkTreeHeadersTest {
  @Test
  public void testSerialization() throws IOException {
    CxxSymlinkTreeHeaders cxxSymlinkTreeHeaders =
        CxxSymlinkTreeHeaders.of(
            CxxPreprocessables.IncludeType.SYSTEM,
            FakeSourcePath.of("root"),
            Either.ofRight(FakeSourcePath.of("includeRoot")),
            Optional.of(FakeSourcePath.of("headerMap")),
            ImmutableSortedMap.of(Paths.get("a/b"), FakeSourcePath.of("path")),
            "treeClass");

    ProjectFilesystem fakeFilesystem = new FakeProjectFilesystem();
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    CxxSymlinkTreeHeaders reconstructed =
        SerializationTestHelper.serializeAndDeserialize(
            cxxSymlinkTreeHeaders,
            CxxSymlinkTreeHeaders.class,
            ruleFinder,
            TestCellPathResolver.create(fakeFilesystem.getRootPath()),
            ruleFinder.getSourcePathResolver(),
            new ToolchainProviderBuilder().build(),
            cellPath -> fakeFilesystem);

    assertEquals(cxxSymlinkTreeHeaders, reconstructed);
  }
}
