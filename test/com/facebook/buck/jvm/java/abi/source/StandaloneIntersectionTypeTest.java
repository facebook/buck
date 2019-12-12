/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java.abi.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiParameterized;
import java.io.IOException;
import java.util.List;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiParameterized.class)
public class StandaloneIntersectionTypeTest extends CompilerTreeApiParameterizedTest {
  @Test
  public void testGetKind() throws IOException {
    compile("class Foo<T extends java.lang.Runnable & java.lang.CharSequence> { }");

    TypeMirror intersectionType = getTypeParameterUpperBound("Foo", 0);

    assertSame(TypeKind.INTERSECTION, intersectionType.getKind());
  }

  @Test
  public void testGetBounds() throws IOException {
    compile("class Foo<T extends java.lang.Runnable & java.lang.CharSequence> { }");

    TypeMirror runnableType = elements.getTypeElement("java.lang.Runnable").asType();
    TypeMirror charSequenceType = elements.getTypeElement("java.lang.CharSequence").asType();

    IntersectionType intersectionType = (IntersectionType) getTypeParameterUpperBound("Foo", 0);
    List<? extends TypeMirror> bounds = intersectionType.getBounds();

    assertSame(2, bounds.size());
    assertSameType(runnableType, bounds.get(0));
    assertSameType(charSequenceType, bounds.get(1));
  }

  @Test
  public void testToString() throws IOException {
    compile("class Foo<T extends java.lang.Runnable & java.lang.CharSequence> { }");

    TypeMirror intersectionType = getTypeParameterUpperBound("Foo", 0);

    assertEquals(
        "java.lang.Object&java.lang.Runnable&java.lang.CharSequence", intersectionType.toString());
  }
}
