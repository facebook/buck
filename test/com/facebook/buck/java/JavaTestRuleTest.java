/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.java;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.step.TargetDevice;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

public class JavaTestRuleTest {

  @Test
  public void testGetClassNamesForSources() {
    Path classesFolder = Paths.get("testdata/javatestrule/default.jar");
    Set<Path> sources = ImmutableSet.of(Paths.get("src/com/facebook/DummyTest.java"));
    Set<String> classNames = JavaTestRule.CompiledClassFileFinder.getClassNamesForSources(sources, classesFolder);
    assertEquals(ImmutableSet.of("com.facebook.DummyTest"), classNames);
  }

  @Test
  public void testGetClassNamesForSourcesWithInnerClasses() {
    Path classesFolder = Paths.get("testdata/javatestrule/case1.jar");
    Set<Path> sources = ImmutableSet.of(Paths.get("src/com/facebook/DummyTest.java"));
    Set<String> classNames = JavaTestRule.CompiledClassFileFinder.getClassNamesForSources(sources, classesFolder);
    assertEquals(ImmutableSet.of("com.facebook.DummyTest"), classNames);
  }

  @Test
  public void testGetClassNamesForSourcesWithMultipleTopLevelClasses() {
    Path classesFolder = Paths.get("testdata/javatestrule/case2.jar");
    Set<Path> sources = ImmutableSet.of(Paths.get("src/com/facebook/DummyTest.java"));
    Set<String> classNames = JavaTestRule.CompiledClassFileFinder.getClassNamesForSources(sources, classesFolder);
    assertEquals(ImmutableSet.of("com.facebook.DummyTest"), classNames);
  }

  @Test
  public void testGetClassNamesForSourcesWithImperfectHeuristic() {
    Path classesFolder = Paths.get("testdata/javatestrule/case2fail.jar");
    Set<Path> sources = ImmutableSet.of(
        Paths.get("src/com/facebook/feed/DummyTest.java"),
        Paths.get("src/com/facebook/nav/OtherDummyTest.java"));
    Set<String> classNames = JavaTestRule.CompiledClassFileFinder.getClassNamesForSources(sources, classesFolder);
    assertEquals("Ideally, if the implementation of getClassNamesForSources() were tightened up,"
        + " the set would not include com.facebook.feed.OtherDummyTest because"
        + " it was not specified in sources.",
        ImmutableSet.of(
            "com.facebook.feed.DummyTest",
            "com.facebook.feed.OtherDummyTest",
            "com.facebook.nav.OtherDummyTest"
        ),
        classNames);
  }

  @Test
  public void shouldNotAmendVmArgsIfTargetDeviceIsNotPresent() {
    List<String> vmArgs = ImmutableList.of("--one", "--two", "--three");
    JavaTestRule rule = newRule(vmArgs);

    List<String> amended = rule.amendVmArgs(vmArgs, Optional.<TargetDevice>absent());

    MoreAsserts.assertListEquals(vmArgs, amended);
  }

  @Test
  public void shouldAddEmulatorTargetDeviceToVmArgsIfPresent() {
    List<String> vmArgs = ImmutableList.of("--one");
    JavaTestRule rule = newRule(vmArgs);

    TargetDevice device = new TargetDevice(TargetDevice.Type.EMULATOR, null);
    List<String> amended = rule.amendVmArgs(vmArgs, Optional.of(device));

    List<String> expected = ImmutableList.of("--one", "-Dbuck.device=emulator");
    assertEquals(expected, amended);
  }

  @Test
  public void shouldAddRealTargetDeviceToVmArgsIfPresent() {
    List<String> vmArgs = ImmutableList.of("--one");
    JavaTestRule rule = newRule(vmArgs);

    TargetDevice device = new TargetDevice(TargetDevice.Type.REAL_DEVICE, null);
    List<String> amended = rule.amendVmArgs(vmArgs, Optional.of(device));

    List<String> expected = ImmutableList.of("--one", "-Dbuck.device=device");
    assertEquals(expected, amended);
  }

  @Test
  public void shouldAddDeviceSerialIdToVmArgsIfPresent() {
    List<String> vmArgs = ImmutableList.of("--one");
    JavaTestRule rule = newRule(vmArgs);

    TargetDevice device = new TargetDevice(TargetDevice.Type.EMULATOR, "123");
    List<String> amended = rule.amendVmArgs(vmArgs, Optional.of(device));

    List<String> expected = ImmutableList.of(
        "--one", "-Dbuck.device=emulator", "-Dbuck.device.id=123");
    assertEquals(expected, amended);
  }

  private JavaTestRule newRule(List<String> vmArgs) {
    return JavaTestRule.newJavaTestRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//example:test"))
        .setVmArgs(vmArgs)
        .addSrc(Paths.get("ExampleTest.java"))
        .build(new BuildRuleResolver());
  }

}
