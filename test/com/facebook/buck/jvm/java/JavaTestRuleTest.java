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

package com.facebook.buck.jvm.java;

import static org.easymock.EasyMock.createMock;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.android.device.TargetDevice;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class JavaTestRuleTest {

  @Test
  public void shouldNotAmendVmArgsIfTargetDeviceIsNotPresent() {
    ImmutableList<String> vmArgs = ImmutableList.of("--one", "--two", "--three");
    JavaTest rule = newRule(vmArgs);

    ImmutableList<String> amended =
        rule.amendVmArgs(
            vmArgs, createMock(SourcePathResolver.class), Optional.empty(), Optional.empty());

    MoreAsserts.assertListEquals(vmArgs, amended);
  }

  @Test
  public void shouldAddEmulatorTargetDeviceToVmArgsIfPresent() {
    ImmutableList<String> vmArgs = ImmutableList.of("--one");
    JavaTest rule = newRule(vmArgs);

    TargetDevice device = new TargetDevice(TargetDevice.Type.EMULATOR, Optional.empty());
    ImmutableList<String> amended =
        rule.amendVmArgs(
            vmArgs, createMock(SourcePathResolver.class), Optional.of(device), Optional.empty());

    ImmutableList<String> expected = ImmutableList.of("--one", "-Dbuck.device=emulator");
    assertEquals(expected, amended);
  }

  @Test
  public void shouldAddRealTargetDeviceToVmArgsIfPresent() {
    ImmutableList<String> vmArgs = ImmutableList.of("--one");
    JavaTest rule = newRule(vmArgs);

    TargetDevice device = new TargetDevice(TargetDevice.Type.REAL_DEVICE, Optional.empty());
    ImmutableList<String> amended =
        rule.amendVmArgs(
            vmArgs, createMock(SourcePathResolver.class), Optional.of(device), Optional.empty());

    ImmutableList<String> expected = ImmutableList.of("--one", "-Dbuck.device=device");
    assertEquals(expected, amended);
  }

  @Test
  public void shouldAddDeviceSerialIdToVmArgsIfPresent() {
    ImmutableList<String> vmArgs = ImmutableList.of("--one");
    JavaTest rule = newRule(vmArgs);

    TargetDevice device = new TargetDevice(TargetDevice.Type.EMULATOR, Optional.of("123"));
    List<String> amended =
        rule.amendVmArgs(
            vmArgs, createMock(SourcePathResolver.class), Optional.of(device), Optional.empty());

    List<String> expected =
        ImmutableList.of("--one", "-Dbuck.device=emulator", "-Dbuck.device.id=123");
    assertEquals(expected, amended);
  }

  @Test
  public void shouldAddJavaTempDirToVmArgs() {
    ImmutableList<String> vmArgs = ImmutableList.of("--one");
    JavaTest rule = newRule(vmArgs);

    List<String> amended =
        rule.amendVmArgs(
            vmArgs, createMock(SourcePathResolver.class), Optional.empty(), Optional.of("path"));

    List<String> expected = ImmutableList.of("--one", "-Djava.io.tmpdir=path");
    assertEquals(expected, amended);
  }

  @Test
  public void transitiveLibraryDependenciesAreRuntimeDeps() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);

    FakeJavaLibrary transitiveDep =
        graphBuilder.addToIndex(
            new FakeJavaLibrary(BuildTargetFactory.newInstance("//:transitive_dep")));

    FakeJavaLibrary firstOrderDep =
        graphBuilder.addToIndex(
            new FakeJavaLibrary(
                BuildTargetFactory.newInstance("//:first_order_dep"),
                ImmutableSortedSet.of(transitiveDep)));

    JavaTest rule =
        JavaTestBuilder.createBuilder(BuildTargetFactory.newInstance("//:rule"))
            .addSrc(Paths.get("ExampleTest.java"))
            .addDep(firstOrderDep.getBuildTarget())
            .build(graphBuilder);

    assertThat(
        rule.getRuntimeDeps(ruleFinder).collect(ImmutableSet.toImmutableSet()),
        Matchers.hasItems(
            rule.getCompiledTestsLibrary().getBuildTarget(),
            firstOrderDep.getBuildTarget(),
            transitiveDep.getBuildTarget()));
  }

  private JavaTest newRule(ImmutableList<String> vmArgs) throws NoSuchBuildTargetException {
    ImmutableList<StringWithMacros> vmArgMacros =
        vmArgs
            .stream()
            .map(arg -> StringWithMacros.of(ImmutableList.of(Either.ofLeft(arg))))
            .collect(ImmutableList.toImmutableList());
    return JavaTestBuilder.createBuilder(BuildTargetFactory.newInstance("//example:test"))
        .setVmArgs(vmArgMacros)
        .addSrc(Paths.get("ExampleTest.java"))
        .build(new TestActionGraphBuilder());
  }
}
