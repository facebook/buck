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

package com.facebook.buck.rules.coercer;

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetFactory;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuildTargetTypeCoercerTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  private ProjectFilesystem filesystem = new FakeProjectFilesystem();
  private Path basePath = Paths.get("java/com/facebook/buck/example");

  private UnconfiguredBuildTargetTypeCoercer unconfiguredBuildTargetTypeCoercer;

  @Before
  public void setUp() throws Exception {
    unconfiguredBuildTargetTypeCoercer =
        new UnconfiguredBuildTargetTypeCoercer(new ParsingUnconfiguredBuildTargetFactory());
  }

  @Test
  public void canCoerceAnUnflavoredFullyQualifiedTarget() throws CoerceFailedException {
    BuildTarget seen =
        new BuildTargetTypeCoercer(unconfiguredBuildTargetTypeCoercer)
            .coerce(
                createCellRoots(filesystem),
                filesystem,
                basePath,
                EmptyTargetConfiguration.INSTANCE,
                "//foo:bar");

    assertEquals(BuildTargetFactory.newInstance("//foo:bar"), seen);
  }

  @Test
  public void failedCoerce() throws CoerceFailedException {
    thrown.expect(CoerceFailedException.class);
    thrown.expectMessage(containsString("Unable to find the target //foo::bar."));
    new BuildTargetTypeCoercer(unconfiguredBuildTargetTypeCoercer)
        .coerce(
            createCellRoots(filesystem),
            filesystem,
            basePath,
            EmptyTargetConfiguration.INSTANCE,
            "//foo::bar");
  }

  @Test
  public void shouldCoerceAShortTarget() throws CoerceFailedException {
    BuildTarget seen =
        new BuildTargetTypeCoercer(unconfiguredBuildTargetTypeCoercer)
            .coerce(
                createCellRoots(filesystem),
                filesystem,
                basePath,
                EmptyTargetConfiguration.INSTANCE,
                ":bar");

    assertEquals(BuildTargetFactory.newInstance("//java/com/facebook/buck/example:bar"), seen);
  }

  @Test
  public void shouldCoerceATargetWithASingleFlavor() throws CoerceFailedException {
    BuildTarget seen =
        new BuildTargetTypeCoercer(unconfiguredBuildTargetTypeCoercer)
            .coerce(
                createCellRoots(filesystem),
                filesystem,
                basePath,
                EmptyTargetConfiguration.INSTANCE,
                "//foo:bar#baz");

    assertEquals(BuildTargetFactory.newInstance("//foo:bar#baz"), seen);
  }

  @Test
  public void shouldCoerceMultipleFlavors() throws CoerceFailedException {
    BuildTarget seen =
        new BuildTargetTypeCoercer(unconfiguredBuildTargetTypeCoercer)
            .coerce(
                createCellRoots(filesystem),
                filesystem,
                basePath,
                EmptyTargetConfiguration.INSTANCE,
                "//foo:bar#baz,qux");

    assertEquals(BuildTargetFactory.newInstance("//foo:bar#baz,qux"), seen);
  }

  @Test
  public void shouldCoerceAShortTargetWithASingleFlavor() throws CoerceFailedException {
    BuildTarget seen =
        new BuildTargetTypeCoercer(unconfiguredBuildTargetTypeCoercer)
            .coerce(
                createCellRoots(filesystem),
                filesystem,
                basePath,
                EmptyTargetConfiguration.INSTANCE,
                ":bar#baz");

    BuildTarget expected =
        BuildTargetFactory.newInstance("//java/com/facebook/buck/example:bar#baz");
    assertEquals(expected, seen);
  }

  @Test
  public void shouldCoerceAWindowsStylePathCorrectly() throws CoerceFailedException {
    // EasyMock doesn't stub out toString, equals, hashCode or finalize. An attempt to hack round
    // this using the MockBuilder failed with an InvocationTargetException. Turns out that easymock
    // just can't mock toString. So we're going to do this Old Skool using a dynamic proxy. *sigh*
    // And we can't build a partial mock from an interface. *sigh*
    Path concreteType = Paths.get("notused");

    Path stubPath =
        (Path)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[] {Path.class},
                (proxy, method, args) -> {
                  if ("toString".equals(method.getName())) {
                    return "foo\\bar";
                  }
                  return method.invoke(concreteType, args);
                });

    BuildTarget seen =
        new BuildTargetTypeCoercer(unconfiguredBuildTargetTypeCoercer)
            .coerce(
                createCellRoots(filesystem),
                filesystem,
                stubPath,
                EmptyTargetConfiguration.INSTANCE,
                ":baz");

    BuildTarget expected = BuildTargetFactory.newInstance("//foo/bar:baz");
    assertEquals(expected, seen);
  }
}
