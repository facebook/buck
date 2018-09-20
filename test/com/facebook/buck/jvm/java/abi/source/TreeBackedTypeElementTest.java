/*
 * Copyright 2016-present Facebook, Inc.
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
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiParameterized;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.SimpleElementVisitor8;
import org.hamcrest.Matchers;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiParameterized.class)
public class TreeBackedTypeElementTest extends CompilerTreeApiParameterizedTest {

  @Test
  public void testGetSimpleName() throws IOException {
    compile("public class Foo {}");
    TypeElement element = elements.getTypeElement("Foo");

    assertNameEquals("Foo", element.getSimpleName());
  }

  @Test
  public void testGetQualifiedNameUnnamedPackage() throws IOException {
    compile("public class Foo {}");

    TypeElement element = elements.getTypeElement("Foo");

    assertNameEquals("Foo", element.getQualifiedName());
  }

  @Test
  public void testGetQualifiedNameNamedPackage() throws IOException {
    compile(
        String.join(System.lineSeparator(), "package com.facebook.buck;", "public class Foo {}"));

    TypeElement element = elements.getTypeElement("com.facebook.buck.Foo");

    assertNameEquals("com.facebook.buck.Foo", element.getQualifiedName());
  }

  @Test
  public void testGetKindAnnotation() throws IOException {
    compile("@interface Foo { }");

    assertSame(ElementKind.ANNOTATION_TYPE, elements.getTypeElement("Foo").getKind());
  }

  @Test
  public void testGetKindClass() throws IOException {
    compile("class Foo { }");

    assertSame(ElementKind.CLASS, elements.getTypeElement("Foo").getKind());
  }

  @Test
  public void testGetKindEnum() throws IOException {
    compile("enum Foo {  }");

    assertSame(ElementKind.ENUM, elements.getTypeElement("Foo").getKind());
  }

  @Test
  public void testGetKindInterface() throws IOException {
    compile("interface Foo { }");

    assertSame(ElementKind.INTERFACE, elements.getTypeElement("Foo").getKind());
  }

  @Test
  public void testAccept() throws IOException {
    compile("class Foo { }");

    TypeElement expectedType = elements.getTypeElement("Foo");
    Object expectedResult = new Object();
    Object actualResult =
        expectedType.accept(
            new SimpleElementVisitor8<Object, Object>() {
              @Override
              protected Object defaultAction(Element e, Object o) {
                return null;
              }

              @Override
              public Object visitType(TypeElement actualType, Object o) {
                assertSame(expectedType, actualType);
                return o;
              }
            },
            expectedResult);

    assertSame(expectedResult, actualResult);
  }

  @Test
  public void testToString() throws IOException {
    compile(
        String.join(
            System.lineSeparator(), "package com.facebook.buck;", "public class Foo<T> {}"));

    TypeElement element = elements.getTypeElement("com.facebook.buck.Foo");

    assertEquals("com.facebook.buck.Foo", element.toString());
  }

  @Test
  public void testGetQualifiedNameInnerClass() throws IOException {
    compile(
        String.join(
            System.lineSeparator(),
            "package com.facebook.buck;",
            "public class Foo {",
            "  public class Bar { }",
            "}"));

    TypeElement element = elements.getTypeElement("com.facebook.buck.Foo.Bar");

    assertNameEquals("com.facebook.buck.Foo.Bar", element.getQualifiedName());
  }

  @Test
  public void testGetQualifiedNameEnumAnonymousMember() throws IOException {
    compile(
        String.join(
            System.lineSeparator(), "public enum Foo {", "  BAR,", "  BAZ() {", "  }", "}"));

    // Expect no crash. Enum anonymous members don't have qualified names, but at one point during
    // development we would crash trying to make one for them.
  }

  @Test
  public void testGetNestingKindTopLevel() throws IOException {
    compile("public class Foo { }");

    assertSame(NestingKind.TOP_LEVEL, elements.getTypeElement("Foo").getNestingKind());
  }

  @Test
  public void testGetNestingKindMember() throws IOException {
    compile(String.join(System.lineSeparator(), "public class Foo {", "  class Bar { }", "}"));

    assertSame(NestingKind.MEMBER, elements.getTypeElement("Foo.Bar").getNestingKind());
  }

  @Test
  public void testAsType() throws IOException {
    compile("class Foo { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    TypeMirror fooTypeMirror = fooElement.asType();

    assertEquals(TypeKind.DECLARED, fooTypeMirror.getKind());
    DeclaredType fooDeclaredType = (DeclaredType) fooTypeMirror;
    assertSame(fooElement, fooDeclaredType.asElement());
    assertEquals(0, fooDeclaredType.getTypeArguments().size());

    TypeMirror enclosingType = fooDeclaredType.getEnclosingType();
    assertEquals(TypeKind.NONE, enclosingType.getKind());
    assertTrue(enclosingType instanceof NoType);
  }

  @Test
  public void testAsTypeGeneric() throws IOException {
    compile("class Foo<T> { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    TypeMirror fooTypeMirror = fooElement.asType();

    assertEquals(TypeKind.DECLARED, fooTypeMirror.getKind());
    DeclaredType fooDeclaredType = (DeclaredType) fooTypeMirror;
    assertSame(fooElement, fooDeclaredType.asElement());
    List<? extends TypeMirror> typeArguments = fooDeclaredType.getTypeArguments();
    assertEquals("T", ((TypeVariable) typeArguments.get(0)).asElement().getSimpleName().toString());
    assertEquals(1, typeArguments.size());

    TypeMirror enclosingType = fooDeclaredType.getEnclosingType();
    assertEquals(TypeKind.NONE, enclosingType.getKind());
    assertTrue(enclosingType instanceof NoType);
  }

  @Test
  public void testGetSuperclassNoSuperclassIsObject() throws IOException {
    compile("class Foo { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    DeclaredType superclass = (DeclaredType) fooElement.getSuperclass();
    TypeElement objectElement = elements.getTypeElement("java.lang.Object");

    assertSame(objectElement, superclass.asElement());
  }

  @Test
  public void testGetSuperclassObjectSuperclassIsObject() throws IOException {
    compile("class Foo extends java.lang.Object { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    DeclaredType superclass = (DeclaredType) fooElement.getSuperclass();
    TypeElement objectElement = elements.getTypeElement("java.lang.Object");

    assertSame(objectElement, superclass.asElement());
  }

  @Test
  public void testGetSuperclassOfInterfaceIsNoneType() throws IOException {
    compile("interface Foo extends java.lang.Runnable { }");

    TypeElement fooElement = elements.getTypeElement("Foo");

    assertSame(TypeKind.NONE, fooElement.getSuperclass().getKind());
  }

  @Test
  public void testGetSuperclassOfEnumIsEnumWithArgs() throws IOException {
    compile("enum Foo { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    DeclaredType superclass = (DeclaredType) fooElement.getSuperclass();

    TypeElement enumElement = elements.getTypeElement("java.lang.Enum");
    TypeMirror expectedSuperclass = types.getDeclaredType(enumElement, fooElement.asType());

    assertSameType(expectedSuperclass, superclass);
  }

  @Test
  public void testGetSuperclassOtherSuperclass() throws IOException {
    compile(
        ImmutableMap.of(
            "Foo.java",
            String.join(
                System.lineSeparator(),
                "package com.facebook.foo;",
                "public class Foo extends com.facebook.bar.Bar { }"),
            "Bar.java",
            String.join(
                System.lineSeparator(), "package com.facebook.bar;", "public class Bar { }")));

    TypeElement fooElement = elements.getTypeElement("com.facebook.foo.Foo");
    TypeElement barElement = elements.getTypeElement("com.facebook.bar.Bar");

    DeclaredType superclass = (DeclaredType) fooElement.getSuperclass();
    assertSame(barElement, superclass.asElement());
  }

  @Test
  public void testGetInterfacesOnClass() throws IOException {
    compile(
        String.join(
            System.lineSeparator(),
            "package com.facebook.foo;",
            "public abstract class Foo implements Runnable, java.io.Closeable { }"));

    TypeElement fooElement = elements.getTypeElement("com.facebook.foo.Foo");
    TypeMirror runnableType = elements.getTypeElement("java.lang.Runnable").asType();
    TypeMirror closeableType = elements.getTypeElement("java.io.Closeable").asType();

    List<? extends TypeMirror> interfaces = fooElement.getInterfaces();
    assertSameType(runnableType, interfaces.get(0));
    assertSameType(closeableType, interfaces.get(1));
    assertEquals(2, interfaces.size());
  }

  @Test
  public void testGetInterfacesOnInterface() throws IOException {
    compile(
        String.join(
            System.lineSeparator(),
            "package com.facebook.foo;",
            "public interface Foo extends Runnable, java.io.Closeable { }"));

    TypeElement fooElement = elements.getTypeElement("com.facebook.foo.Foo");
    TypeMirror runnableType = elements.getTypeElement("java.lang.Runnable").asType();
    TypeMirror closeableType = elements.getTypeElement("java.io.Closeable").asType();

    List<? extends TypeMirror> interfaces = fooElement.getInterfaces();
    assertSameType(runnableType, interfaces.get(0));
    assertSameType(closeableType, interfaces.get(1));
    assertEquals(2, interfaces.size());
  }

  @Test
  public void testGetInterfacesDefaultsEmptyForClass() throws IOException {
    compile(
        String.join(System.lineSeparator(), "package com.facebook.foo;", "public class Foo { }"));

    TypeElement fooElement = elements.getTypeElement("com.facebook.foo.Foo");
    assertThat(fooElement.getInterfaces(), Matchers.empty());
  }

  @Test
  public void testGetInterfacesDefaultsEmptyForInterface() throws IOException {
    compile(
        String.join(
            System.lineSeparator(), "package com.facebook.foo;", "public interface Foo { }"));

    TypeElement fooElement = elements.getTypeElement("com.facebook.foo.Foo");
    assertThat(fooElement.getInterfaces(), Matchers.empty());
  }

  @Test
  public void testGetInterfacesDefaultsEmptyForEnum() throws IOException {
    compile(
        String.join(System.lineSeparator(), "package com.facebook.foo;", "public enum Foo { }"));

    TypeElement fooElement = elements.getTypeElement("com.facebook.foo.Foo");
    assertThat(fooElement.getInterfaces(), Matchers.empty());
  }

  @Test
  public void testGetInterfacesDefaultsAnnotationForAnnotation() throws IOException {
    compile(
        String.join(
            System.lineSeparator(), "package com.facebook.foo;", "public @interface Foo { }"));

    TypeElement fooElement = elements.getTypeElement("com.facebook.foo.Foo");
    TypeMirror annotationType = elements.getTypeElement("java.lang.annotation.Annotation").asType();

    List<? extends TypeMirror> interfaces = fooElement.getInterfaces();
    assertSameType(annotationType, interfaces.get(0));
    assertEquals(1, interfaces.size());
  }

  @Test
  public void testEnclosedClasses() throws IOException {
    compile(String.join(System.lineSeparator(), "class Foo {", "  class Bar { }", "}"));

    TypeElement fooElement = elements.getTypeElement("Foo");
    TypeElement barElement = elements.getTypeElement("Foo.Bar");

    List<Element> enclosedElements = new ArrayList<>(fooElement.getEnclosedElements());
    assertThat(enclosedElements, Matchers.hasItems(barElement));
    assertSame(fooElement, barElement.getEnclosingElement());
  }

  @Test
  public void testGetEnclosingElementForTopLevelClasses() throws IOException {
    compile("class Foo { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    PackageElement unnamedPackage = elements.getPackageElement("");

    assertSame(unnamedPackage, fooElement.getEnclosingElement());
    assertTrue(unnamedPackage.getEnclosedElements().contains(fooElement));
  }

  @Ignore(
      "TODO(jkeljo): Need to wrap elements coming out of javac to ensure callers always get"
          + "our PackageElement impl instead of javac's.")
  @Test
  public void testGetEnclosingElementForBuiltInTopLevelClasses() throws IOException {
    initCompiler();

    TypeElement stringElement = elements.getTypeElement("java.lang.String");
    PackageElement javaLangElement = elements.getPackageElement("java.lang");

    assertSame(javaLangElement, stringElement.getEnclosingElement());
  }

  @Test
  public void testIncludesGeneratedDefaultConstructor() throws IOException {
    compile("class Foo { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    ExecutableElement constructorElement =
        (ExecutableElement) fooElement.getEnclosedElements().get(0);

    assertEquals("<init>", constructorElement.getSimpleName().toString());
    assertSameType(types.getNoType(TypeKind.VOID), constructorElement.getReturnType());
    assertThat(constructorElement.getParameters(), Matchers.empty());
    assertThat(constructorElement.getTypeParameters(), Matchers.empty());
    assertThat(constructorElement.getThrownTypes(), Matchers.empty());
    assertEquals(1, fooElement.getEnclosedElements().size());
  }

  @Test
  public void testIncludesGeneratedEnumMembers() throws IOException {
    compile("enum Foo { }");

    TypeElement fooElement = elements.getTypeElement("Foo");

    List<ExecutableElement> methods = ElementFilter.methodsIn(fooElement.getEnclosedElements());
    ExecutableElement valuesMethod = methods.get(0);
    assertEquals("values", valuesMethod.getSimpleName().toString());
    assertSameType(types.getArrayType(fooElement.asType()), valuesMethod.getReturnType());
    assertThat(valuesMethod.getParameters(), Matchers.empty());
    assertThat(valuesMethod.getTypeParameters(), Matchers.empty());
    assertThat(valuesMethod.getThrownTypes(), Matchers.empty());

    ExecutableElement valueOfMethod = methods.get(1);
    assertEquals("valueOf", valueOfMethod.getSimpleName().toString());
    assertSameType(fooElement.asType(), valueOfMethod.getReturnType());
    List<? extends VariableElement> parameters = valueOfMethod.getParameters();
    assertSameType(
        elements.getTypeElement("java.lang.String").asType(), parameters.get(0).asType());
    assertEquals(1, parameters.size());
    assertThat(valuesMethod.getTypeParameters(), Matchers.empty());
    assertThat(valuesMethod.getThrownTypes(), Matchers.empty());

    assertEquals(2, methods.size());
  }

  /**
   * javac has this bug where getTypeDecls thinks that once it sees a non-ImportTree, everything
   * else must be a type. Not so; it's perfectly legal (if odd) to have an EmptyStatementTree in the
   * midst of ImportTrees.
   */
  @Test
  public void testEnterElementEvenWithEmptyStatementsInImports() throws IOException {
    compile(
        String.join(
            System.lineSeparator(),
            "import java.io.IOException;;",
            "import java.io.InputStream;",
            "public class Foo { }"));

    TypeElement fooElement = elements.getTypeElement("Foo");
    assertEquals("Foo", fooElement.getQualifiedName().toString());
  }

  @Test
  public void testGracefulErrorOnNonexistentMemberType() throws IOException {
    if (!testingTrees()) {
      return;
    }
    testCompiler.setAllowCompilationErrors(true);
    compile(String.join(System.lineSeparator(), "public class Foo {", "  Foo.Bar b;", "}"));

    // Note: the compiler Diagnostics emit 2 \n characthers when emitting '    ^' error.
    // That is why we go through the hassle below.n

    String result =
        String.join(
                System.lineSeparator(),
                "Foo.java:2: error: cannot find symbol generating source-only ABI",
                "  Foo.Bar b;")
            + "\n     ^\n"
            + "  Build the #source-abi flavor of this rule to see if the symbol is truly missing or if the rule just needs a source_only_abi_dep.";
    assertError(result);
  }
}
