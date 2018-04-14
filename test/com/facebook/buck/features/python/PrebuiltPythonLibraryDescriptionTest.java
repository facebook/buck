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

package com.facebook.buck.features.python;

import static org.junit.Assert.assertThat;

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkStrategy;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import org.hamcrest.Matchers;
import org.junit.Test;

public class PrebuiltPythonLibraryDescriptionTest {

  @Test
  public void excludingTransitiveNativeDepsUsingMergedNativeLinkStrategy() {
    CxxLibraryBuilder cxxDepBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("dep.c"))));
    CxxLibraryBuilder cxxBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:cxx"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("cxx.c"))))
            .setDeps(ImmutableSortedSet.of(cxxDepBuilder.getTarget()));
    PrebuiltPythonLibraryBuilder libBuilder =
        new PrebuiltPythonLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setDeps(ImmutableSortedSet.of(cxxBuilder.getTarget()))
            .setBinarySrc(FakeSourcePath.of("test.whl"))
            .setExcludeDepsFromMergedLinking(true);

    PythonBuckConfig config =
        new PythonBuckConfig(FakeBuckConfig.builder().build()) {
          @Override
          public NativeLinkStrategy getNativeLinkStrategy() {
            return NativeLinkStrategy.MERGED;
          }
        };
    PythonBinaryBuilder binaryBuilder =
        PythonBinaryBuilder.create(
            BuildTargetFactory.newInstance("//:bin"), config, PythonTestUtils.PYTHON_PLATFORMS);
    binaryBuilder.setMainModule("main");
    binaryBuilder.setDeps(ImmutableSortedSet.of(libBuilder.getTarget()));

    BuildRuleResolver resolver =
        new TestBuildRuleResolver(
            TargetGraphFactory.newInstance(
                cxxDepBuilder.build(),
                cxxBuilder.build(),
                libBuilder.build(),
                binaryBuilder.build()));
    cxxDepBuilder.build(resolver);
    cxxBuilder.build(resolver);
    libBuilder.build(resolver);
    PythonBinary binary = binaryBuilder.build(resolver);
    assertThat(
        Iterables.transform(binary.getComponents().getNativeLibraries().keySet(), Object::toString),
        Matchers.containsInAnyOrder("libdep.so", "libcxx.so"));
  }
}
