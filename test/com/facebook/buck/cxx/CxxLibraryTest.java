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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxLibraryTest {

  @Test
  public void cxxLibraryInterfaces() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);

    // Setup some dummy values for the header info.
    final BuildTarget headerTarget = BuildTargetFactory.newInstance("//:header");
    final BuildTarget headerSymlinkTreeTarget = BuildTargetFactory.newInstance("//:symlink");
    final Path headerSymlinkTreeRoot = Paths.get("symlink/tree/root");

    // Setup some dummy values for the library archive info.
    final BuildTarget archiveTarget = BuildTargetFactory.newInstance("//:archive");
    final Path archiveOutput = Paths.get("output/path/lib.a");

    // Construct a CxxLibrary object to test.
    CxxLibrary cxxLibrary = new CxxLibrary(params) {

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput() {
        return new CxxPreprocessorInput(
            ImmutableSet.of(headerTarget, headerSymlinkTreeTarget),
            ImmutableList.<String>of(),
            ImmutableList.<String>of(),
            ImmutableList.of(headerSymlinkTreeRoot),
            ImmutableList.<Path>of());
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput() {
        return new NativeLinkableInput(
            ImmutableSet.of(archiveTarget),
            ImmutableList.of(archiveOutput),
            ImmutableList.of(archiveOutput.toString()));
      }

    };

    // Verify that we get the header/symlink targets and root via the CxxPreprocessorDep
    // interface.
    CxxPreprocessorInput expectedCxxPreprocessorInput = new CxxPreprocessorInput(
        ImmutableSet.of(headerTarget, headerSymlinkTreeTarget),
        ImmutableList.<String>of(),
        ImmutableList.<String>of(),
        ImmutableList.of(headerSymlinkTreeRoot),
        ImmutableList.<Path>of());
    assertEquals(expectedCxxPreprocessorInput, cxxLibrary.getCxxPreprocessorInput());

    // Verify that we get the static archive and it's build target via the NativeLinkable
    // interface.
    NativeLinkableInput expectedNativeLinkableInput = new NativeLinkableInput(
        ImmutableSet.of(archiveTarget),
        ImmutableList.of(archiveOutput),
        ImmutableList.of(archiveOutput.toString()));
    assertEquals(expectedNativeLinkableInput, cxxLibrary.getNativeLinkableInput());

    // Verify that the implemented BuildRule methods are effectively unused.
    assertEquals(ImmutableList.<Step>of(), cxxLibrary.getBuildSteps(null, null));
    assertNull(cxxLibrary.getPathToOutputFile());
    assertTrue(ImmutableList.copyOf(cxxLibrary.getInputsToCompareToOutput()).isEmpty());
  }

}
