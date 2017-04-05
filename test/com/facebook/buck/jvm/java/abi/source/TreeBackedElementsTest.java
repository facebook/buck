/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.abi.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.jvm.java.testutil.CompilerTreeApiParameterized;
import com.google.common.base.Joiner;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

@RunWith(CompilerTreeApiParameterized.class)
public class TreeBackedElementsTest extends CompilerTreeApiParameterizedTest {
  @Test
  public void testGetBinaryNameTopLevelClass() throws IOException {
    compile(Joiner.on('\n').join(
        "package com.facebook.foo;",
        "class Foo { }"));

    assertEquals(
        "com.facebook.foo.Foo",
        elements.getBinaryName(elements.getTypeElement("com.facebook.foo.Foo")).toString());
  }

  @Test
  public void testGetBinaryNameInnerClass() throws IOException {
    compile(Joiner.on('\n').join(
        "package com.facebook.foo;",
        "class Foo {",
        "  class Inner {}",
        "}"));

    assertEquals(
        "com.facebook.foo.Foo$Inner",
        elements.getBinaryName(elements.getTypeElement("com.facebook.foo.Foo.Inner")).toString());
  }

  @Test
  public void testGetDocComment() throws IOException {
    compile(Joiner.on('\n').join(
        "package com.facebook.foo;",
        "/** I am a doc comment. */",
        "class Foo { }"));

    assertEquals(
        "I am a doc comment. ",
        elements.getDocComment(elements.getTypeElement("com.facebook.foo.Foo")));
  }

  @Test
  public void testIsDeprecated() throws IOException {
    compile(Joiner.on('\n').join(
        "package com.facebook.foo;",
        "@Deprecated",
        "class Foo { }"));

    assertTrue(
        elements.isDeprecated(elements.getTypeElement("com.facebook.foo.Foo")));
  }

  @Test
  public void testGetPackageOf() throws IOException {
    compile(Joiner.on('\n').join(
        "package com.facebook.foo;",
        "@Deprecated",
        "class Foo { }"));

    assertSame(
        elements.getPackageElement("com.facebook.foo"),
        elements.getPackageOf(elements.getTypeElement("com.facebook.foo.Foo")));
  }
}
