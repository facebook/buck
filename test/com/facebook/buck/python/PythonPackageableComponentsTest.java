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

package com.facebook.buck.python;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PythonPackageableComponentsTest {

  @Test
  public void testMergeZipSafe() {
    PythonPackageComponents compA = PythonPackageComponents.of(
        ImmutableMap.<Path, SourcePath>of(Paths.get("test"), new FakeSourcePath("sourceA")),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableSet.<SourcePath>of(),
        Optional.of(true));
    PythonPackageComponents compB = PythonPackageComponents.of(
        ImmutableMap.<Path, SourcePath>of(Paths.get("test2"), new FakeSourcePath("sourceB")),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableSet.<SourcePath>of(),
        Optional.of(false));

    BuildTarget me = BuildTargetFactory.newInstance("//:me");
    BuildTarget them = BuildTargetFactory.newInstance("//:them");
    PythonPackageComponents.Builder builder = new PythonPackageComponents.Builder(me);
    builder.addComponent(compB, them);
    builder.addComponent(compA, them);
    assertFalse(builder.build().isZipSafe().get());
  }

  @Test
  public void testDuplicateSourcesThrowsException() {
    BuildTarget me = BuildTargetFactory.newInstance("//:me");
    BuildTarget them = BuildTargetFactory.newInstance("//:them");
    PythonPackageComponents.Builder builder = new PythonPackageComponents.Builder(me);
    Path dest = Paths.get("test");
    builder.addModule(dest, new FakeSourcePath("sourceA"), them);
    try {
      builder.addModule(dest, new FakeSourcePath("sourceB"), them);
      fail("expected to throw");
    } catch (HumanReadableException e) {
      assertTrue(e.getMessage().contains("duplicate entries"));
    }
  }

  @Test
  public void testDuplicateSourcesInComponentsThrowsException() {
    BuildTarget me = BuildTargetFactory.newInstance("//:me");
    BuildTarget them = BuildTargetFactory.newInstance("//:them");
    Path dest = Paths.get("test");
    PythonPackageComponents compA = PythonPackageComponents.of(
        ImmutableMap.<Path, SourcePath>of(dest, new FakeSourcePath("sourceA")),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableSet.<SourcePath>of(),
        Optional.<Boolean>absent());
    PythonPackageComponents compB = PythonPackageComponents.of(
        ImmutableMap.<Path, SourcePath>of(dest, new FakeSourcePath("sourceB")),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableSet.<SourcePath>of(),
        Optional.<Boolean>absent());
    PythonPackageComponents.Builder builder = new PythonPackageComponents.Builder(me);
    builder.addComponent(compA, them);
    try {
      builder.addComponent(compB, them);
      fail("expected to throw");
    } catch (HumanReadableException e) {
      assertTrue(e.getMessage().contains("duplicate entries"));
    }
  }

}
