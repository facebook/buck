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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestSourcePath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.nio.file.Path;

public class CxxDescriptionEnhancerTest {

  @Test
  public void linkWhole() {
    FakeBuckConfig buckConfig = new FakeBuckConfig();
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(buckConfig);

    // Setup the target name and build params.
    BuildTarget target = BuildTargetFactory.newInstance("//:test");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);

    String sourceName = "test.cc";
    CxxSource source = new CxxSource(
        sourceName,
        new TestSourcePath(sourceName),
        CxxCompilableEnhancer.getCompileOutputPath(target, sourceName));

    // First, create a cxx library without using link whole.
    CxxLibrary normal = CxxDescriptionEnhancer.createCxxLibraryBuildRules(
        params,
        new BuildRuleResolver(),
        cxxBuckConfig,
        /* preprocessorFlags */ ImmutableList.<String>of(),
        /* propagatedPpFlags */ ImmutableList.<String>of(),
        /* headers */ ImmutableMap.<Path, SourcePath>of(),
        /* compilerFlags */ ImmutableList.<String>of(),
        /* sources */ ImmutableList.of(source),
        /* linkWhole */ false);

    // Verify that the linker args contains the link whole flags.
    assertFalse(normal.getNativeLinkableInput().getArgs().contains("--whole-archive"));
    assertFalse(normal.getNativeLinkableInput().getArgs().contains("--no-whole-archive"));

    // Create a cxx library using link whole.
    CxxLibrary linkWhole = CxxDescriptionEnhancer.createCxxLibraryBuildRules(
        params,
        new BuildRuleResolver(),
        cxxBuckConfig,
        /* preprocessorFlags */ ImmutableList.<String>of(),
        /* propagatedPpFlags */ ImmutableList.<String>of(),
        /* headers */ ImmutableMap.<Path, SourcePath>of(),
        /* compilerFlags */ ImmutableList.<String>of(),
        /* sources */ ImmutableList.of(source),
        /* linkWhole */ true);

    // Verify that the linker args contains the link whole flags.
    assertTrue(linkWhole.getNativeLinkableInput().getArgs().contains("--whole-archive"));
    assertTrue(linkWhole.getNativeLinkableInput().getArgs().contains("--no-whole-archive"));
  }

}
