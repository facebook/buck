/*
 * Copyright 2019-present Facebook, Inc.
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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class CxxConstructorArgTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testCheckDuplicateSourcesFailsDuplicates() {
    ImmutableTestCxxConstructorArg.Builder builder = ImmutableTestCxxConstructorArg.builder();

    // Required attributes
    builder.name("mything");

    // Stuff we actually care about testing
    Path fakePath = Paths.get("a", "pretty", "long", "path.cpp");
    SourcePath sharedPath = PathSourcePath.of(new FakeProjectFilesystem(), fakePath);

    ImmutableSortedSet<SourceWithFlags> srcs =
        ImmutableSortedSet.of(SourceWithFlags.of(sharedPath, Lists.newArrayList("-Dbar", "-Dbaz")));
    PatternMatchedCollection<ImmutableSortedSet<SourceWithFlags>> platformSrcs =
        PatternMatchedCollection.<ImmutableSortedSet<SourceWithFlags>>builder()
            .add(
                Pattern.compile("barbaz"),
                ImmutableSortedSet.of(
                    SourceWithFlags.of(sharedPath, Lists.newArrayList("-DEADBEEF"))))
            .build();

    builder.srcs(srcs);
    builder.platformSrcs(platformSrcs);

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(fakePath.toString());
    thrown.expectMessage("platform_srcs");

    builder.build();
  }
}
