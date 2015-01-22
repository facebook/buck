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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.testutil.FakeProjectFilesystem;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Paths;

public class BuildTargetTypeCoercerTest {

  private BuildTargetParser targetParser = new BuildTargetParser();
  private ProjectFilesystem filesystem = new FakeProjectFilesystem();
  private Path basePath = Paths.get("java/com/facebook/buck/example");

  @Test
  public void canCoerceAnUnflavoredFullyQualifiedTarget() throws CoerceFailedException {
    BuildTarget seen = new BuildTargetTypeCoercer().coerce(
        targetParser,
        filesystem,
        basePath,
        "//foo:bar");

    assertEquals(BuildTarget.builder("//foo", "bar").build(), seen);
  }

  @Test
  public void shouldCoerceAShortTarget() throws CoerceFailedException {
    BuildTarget seen = new BuildTargetTypeCoercer().coerce(
        targetParser,
        filesystem,
        basePath,
        ":bar");

    assertEquals(BuildTarget.builder("//java/com/facebook/buck/example", "bar").build(), seen);
  }

  @Test
  public void shouldCoerceATargetWithASingleFlavor() throws CoerceFailedException {
    BuildTarget seen = new BuildTargetTypeCoercer().coerce(
        targetParser,
        filesystem,
        basePath,
        "//foo:bar#baz");

    assertEquals(
        BuildTarget.builder("//foo", "bar").addFlavors(ImmutableFlavor.of("baz")).build(),
        seen);
  }

  @Test
  public void shouldCoerceMultipleFlavors() throws CoerceFailedException {
    BuildTarget seen = new BuildTargetTypeCoercer().coerce(
        targetParser,
        filesystem,
        basePath,
        "//foo:bar#baz,qux");

    assertEquals(
        BuildTarget
            .builder("//foo", "bar")
            .addFlavors(ImmutableFlavor.of("baz"))
            .addFlavors(ImmutableFlavor.of("qux"))
            .build(),
        seen);
  }

  @Test
  public void shouldCoerceAShortTargetWithASingleFlavor() throws CoerceFailedException {
    BuildTarget seen = new BuildTargetTypeCoercer().coerce(
        targetParser,
        filesystem,
        basePath,
        ":bar#baz");

    BuildTarget expected = BuildTarget.builder("//java/com/facebook/buck/example", "bar")
        .addFlavors(ImmutableFlavor.of("baz"))
        .build();
    assertEquals(expected, seen);
  }

  @Test
  public void shouldCoerceAWindowsStylePathCorrectly() throws CoerceFailedException {
    // EasyMock doesn't stub out toString, equals, hashCode or finalize. An attempt to hack round
    // this using the MockBuilder failed with an InvocationTargetException. Turns out that easymock
    // just can't mock toString. So we're going to do this Old Skool using a dynamic proxy. *sigh*
    // And we can't build a partial mock from an interface. *sigh*
    final Path concreteType = Paths.get("notused");

    Path stubPath = (Path) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class[]{Path.class},
        new InvocationHandler() {
          @Override
          public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("toString".equals(method.getName())) {
              return "foo\\bar";
            }
            return method.invoke(concreteType, args);
          }
        });

    BuildTarget seen = new BuildTargetTypeCoercer().coerce(
        targetParser,
        filesystem,
        stubPath,
        ":baz");

    BuildTarget expected = BuildTarget.builder("//foo/bar", "baz").build();
    assertEquals(expected, seen);
  }
}
