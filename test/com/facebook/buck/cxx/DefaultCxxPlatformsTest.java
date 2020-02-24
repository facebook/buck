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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.ArchiveContents;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;

public class DefaultCxxPlatformsTest {

  @Test
  public void compilerFlagsPropagateToPreprocessorFlags() {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(
            new CxxBuckConfig(
                FakeBuckConfig.builder()
                    .setSections(
                        ImmutableMap.of(
                            "cxx",
                            ImmutableMap.of(
                                "cflags", "-std=gnu11",
                                "cppflags", "-DCFOO",
                                "cxxflags", "-std=c++11",
                                "cxxppflags", "-DCXXFOO")))
                    .build()));
    BuildRuleResolver ruleResolver = new TestActionGraphBuilder();
    SourcePathResolverAdapter resolver = ruleResolver.getSourcePathResolver();
    assertThat(Arg.stringify(cxxPlatform.getCflags(), resolver), containsInAnyOrder("-std=gnu11"));
    assertThat(Arg.stringify(cxxPlatform.getCppflags(), resolver), containsInAnyOrder("-DCFOO"));
    assertThat(
        Arg.stringify(cxxPlatform.getCxxflags(), resolver), containsInAnyOrder("-std=c++11"));
    assertThat(
        Arg.stringify(cxxPlatform.getCxxppflags(), resolver), containsInAnyOrder("-DCXXFOO"));
  }

  @Test
  public void archiveContentsDefault() {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(
            new CxxBuckConfig(
                FakeBuckConfig.builder()
                    .setSections(
                        ImmutableMap.of("cxx", ImmutableMap.of("archive_contents", "thin")))
                    .build()));
    assertEquals(cxxPlatform.getArchiveContents(), ArchiveContents.THIN);
  }
}
