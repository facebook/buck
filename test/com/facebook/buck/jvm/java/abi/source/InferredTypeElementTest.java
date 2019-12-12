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

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTest;
import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTestRunner;
import com.google.common.base.Joiner;
import java.io.IOException;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiTestRunner.class)
public class InferredTypeElementTest extends CompilerTreeApiTest {
  @Before
  public void setUp() {
    testCompiler.useFrontendOnlyJavacTask();
  }

  @Test
  public void testGetSimpleNameInferredFromIdentifier() throws IOException {
    compile(
        Joiner.on('\n').join("package com.example.buck;", "public class Foo extends Bar {", "}"));

    DeclaredType superclass =
        (DeclaredType) elements.getTypeElement("com.example.buck.Foo").getSuperclass();
    TypeElement element = (TypeElement) superclass.asElement();
    assertNameEquals("Bar", element.getSimpleName());
  }

  @Test
  public void testGetQualifiedNameInferredFromIdentifier() throws IOException {
    compile(
        Joiner.on('\n').join("package com.example.buck;", "public class Foo extends Bar {", "}"));

    DeclaredType superclass =
        (DeclaredType) elements.getTypeElement("com.example.buck.Foo").getSuperclass();
    TypeElement element = (TypeElement) superclass.asElement();
    assertNameEquals("com.example.buck.Bar", element.getQualifiedName());
  }

  @Test
  public void testGetSimpleNameInferredFromIdentifierDefaultPackage() throws IOException {
    compile(Joiner.on('\n').join("public class Foo extends Bar {", "}"));

    DeclaredType superclass = (DeclaredType) elements.getTypeElement("Foo").getSuperclass();
    TypeElement element = (TypeElement) superclass.asElement();
    assertNameEquals("Bar", element.getSimpleName());
  }

  @Test
  public void testGetQualifiedNameInferredFromIdentifierDefaultPackage() throws IOException {
    compile(Joiner.on('\n').join("public class Foo extends Bar {", "}"));

    DeclaredType superclass = (DeclaredType) elements.getTypeElement("Foo").getSuperclass();
    TypeElement element = (TypeElement) superclass.asElement();
    assertNameEquals("Bar", element.getQualifiedName());
  }

  @Test
  public void testGetSimpleNameInferredFromQualifiedName() throws IOException {
    compile(Joiner.on('\n').join("public class Foo extends com.example.buck.Bar {", "}"));

    DeclaredType superclass = (DeclaredType) elements.getTypeElement("Foo").getSuperclass();
    TypeElement element = (TypeElement) superclass.asElement();
    assertNameEquals("Bar", element.getSimpleName());
  }

  @Test
  public void testGetQualifiedNameInferredFromQualifiedName() throws IOException {
    compile(Joiner.on('\n').join("public class Foo extends com.example.buck.Bar {", "}"));

    DeclaredType superclass = (DeclaredType) elements.getTypeElement("Foo").getSuperclass();
    TypeElement element = (TypeElement) superclass.asElement();
    assertNameEquals("com.example.buck.Bar", element.getQualifiedName());
  }

  @Test
  public void testMemberClassGetSimpleNameInferredFromQualifiedName() throws IOException {
    compile(Joiner.on('\n').join("public class Foo extends com.example.buck.Bar.Baz {", "}"));

    DeclaredType superclass = (DeclaredType) elements.getTypeElement("Foo").getSuperclass();
    TypeElement element = (TypeElement) superclass.asElement();
    assertNameEquals("Baz", element.getSimpleName());
  }

  @Test
  public void testMemberClassGetQualifiedNameInferredFromQualifiedName() throws IOException {
    compile(Joiner.on('\n').join("public class Foo extends com.example.buck.Bar.Baz {", "}"));

    DeclaredType superclass = (DeclaredType) elements.getTypeElement("Foo").getSuperclass();
    TypeElement element = (TypeElement) superclass.asElement();
    assertNameEquals("com.example.buck.Bar.Baz", element.getQualifiedName());
  }

  @Test
  public void testGetNestingKindTopLevel() throws IOException {
    compile(Joiner.on('\n').join("public class Foo extends com.example.buck.Bar {", "}"));

    DeclaredType superclass = (DeclaredType) elements.getTypeElement("Foo").getSuperclass();
    TypeElement element = (TypeElement) superclass.asElement();
    assertEquals(NestingKind.TOP_LEVEL, element.getNestingKind());
  }

  @Test
  public void testGetNestingKindMember() throws IOException {
    compile(Joiner.on('\n').join("public class Foo extends com.example.buck.Bar.Baz {", "}"));

    DeclaredType superclass = (DeclaredType) elements.getTypeElement("Foo").getSuperclass();
    TypeElement element = (TypeElement) superclass.asElement();
    assertEquals(NestingKind.MEMBER, element.getNestingKind());
  }

  @Test
  public void testAsType() throws IOException {
    compile(Joiner.on('\n').join("public class Foo extends com.example.buck.Bar.Baz {", "}"));

    DeclaredType superclass = (DeclaredType) elements.getTypeElement("Foo").getSuperclass();
    TypeElement element = (TypeElement) superclass.asElement();
    TypeMirror type = element.asType();

    assertEquals(TypeKind.DECLARED, type.getKind());
    DeclaredType fooDeclaredType = (DeclaredType) type;
    assertSame(element, fooDeclaredType.asElement());
  }
}
