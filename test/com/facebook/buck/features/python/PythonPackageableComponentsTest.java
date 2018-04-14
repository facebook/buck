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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSetMultimap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;

public class PythonPackageableComponentsTest {

  @Test
  public void testMergeZipSafe() {
    PythonPackageComponents compA =
        PythonPackageComponents.of(
            ImmutableMap.of(Paths.get("test"), FakeSourcePath.of("sourceA")),
            ImmutableMap.of(),
            ImmutableMap.of(),
            ImmutableMultimap.of(),
            Optional.of(true));
    PythonPackageComponents compB =
        PythonPackageComponents.of(
            ImmutableMap.of(Paths.get("test2"), FakeSourcePath.of("sourceB")),
            ImmutableMap.of(),
            ImmutableMap.of(),
            ImmutableMultimap.of(),
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
    builder.addModule(dest, FakeSourcePath.of("sourceA"), them);
    try {
      builder.addModule(dest, FakeSourcePath.of("sourceB"), them);
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
    PythonPackageComponents compA =
        PythonPackageComponents.of(
            ImmutableMap.of(dest, FakeSourcePath.of("sourceA")),
            ImmutableMap.of(),
            ImmutableMap.of(),
            ImmutableMultimap.of(),
            Optional.empty());
    PythonPackageComponents compB =
        PythonPackageComponents.of(
            ImmutableMap.of(dest, FakeSourcePath.of("sourceB")),
            ImmutableMap.of(),
            ImmutableMap.of(),
            ImmutableMultimap.of(),
            Optional.empty());
    PythonPackageComponents.Builder builder = new PythonPackageComponents.Builder(me);
    builder.addComponent(compA, them);
    try {
      builder.addComponent(compB, them);
      fail("expected to throw");
    } catch (HumanReadableException e) {
      assertTrue(e.getMessage().contains("duplicate entries"));
    }
  }

  @Test
  public void testDuplicateIndeticalSourcesInComponentsIsOk() {
    BuildTarget me = BuildTargetFactory.newInstance("//:me");
    BuildTarget them = BuildTargetFactory.newInstance("//:them");
    Path dest = Paths.get("test");
    SourcePath path = FakeSourcePath.of("source");
    PythonPackageComponents compA =
        PythonPackageComponents.of(
            ImmutableMap.of(dest, path),
            ImmutableMap.of(),
            ImmutableMap.of(),
            ImmutableMultimap.of(),
            Optional.empty());
    PythonPackageComponents compB =
        PythonPackageComponents.of(
            ImmutableMap.of(dest, path),
            ImmutableMap.of(),
            ImmutableMap.of(),
            ImmutableMultimap.of(),
            Optional.empty());
    PythonPackageComponents.Builder builder = new PythonPackageComponents.Builder(me);
    builder.addComponent(compA, them);
    builder.addComponent(compB, them);
    builder.build();
  }

  @Test
  public void testBuilderBuildsAndUpdates() {
    BuildTarget me = BuildTargetFactory.newInstance("//:me");
    BuildTarget them = BuildTargetFactory.newInstance("//:them");

    PythonPackageComponents expected =
        PythonPackageComponents.of(
            ImmutableMap.of(
                Paths.get("fooA"),
                FakeSourcePath.of("sourceA"),
                Paths.get("fooB"),
                FakeSourcePath.of("sourceB")),
            ImmutableMap.of(
                Paths.get("barA"),
                FakeSourcePath.of("resA"),
                Paths.get("barB"),
                FakeSourcePath.of("resB")),
            ImmutableMap.of(
                Paths.get("nativeA.so"),
                FakeSourcePath.of("nativeA"),
                Paths.get("nativeB.so"),
                FakeSourcePath.of("nativeB")),
            ImmutableSetMultimap.of(
                Paths.get(""),
                FakeSourcePath.of("extractedA.whl"),
                Paths.get(""),
                FakeSourcePath.of("extractedB.whl")),
            Optional.empty());

    PythonPackageComponents.Builder builder = new PythonPackageComponents.Builder(me);
    builder.addModule(Paths.get("fooA"), FakeSourcePath.of("sourceA"), them);
    builder.addResources(ImmutableMap.of(Paths.get("barA"), FakeSourcePath.of("resA")), them);
    builder.addNativeLibraries(
        ImmutableMap.of(Paths.get("nativeA.so"), FakeSourcePath.of("nativeA")), them);
    builder.addModuleDirs(
        ImmutableSetMultimap.of(Paths.get(""), FakeSourcePath.of("extractedA.whl")));

    builder.addModule(Paths.get("fooB"), FakeSourcePath.of("sourceB"), them);
    builder.addResources(ImmutableMap.of(Paths.get("barB"), FakeSourcePath.of("resB")), them);
    builder.addNativeLibraries(
        ImmutableMap.of(Paths.get("nativeB.so"), FakeSourcePath.of("nativeB")), them);
    builder.addModuleDirs(
        ImmutableSetMultimap.of(Paths.get(""), FakeSourcePath.of("extractedB.whl")));

    Assert.assertEquals(expected, builder.build());
  }
}
