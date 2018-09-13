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

package com.facebook.buck.jvm.java.abi;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.jvm.java.lang.model.ElementsExtended;
import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTestRunner;
import com.facebook.buck.jvm.java.testutil.compiler.TestCompiler;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExternalResource;
import org.junit.runner.RunWith;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

@RunWith(CompilerTreeApiTestRunner.class)
public class InnerClassesTableTest {
  @Rule public Tester tester = new Tester();

  // Tests that the table is ordered properly in various cases.
  // region OrderingTests
  @Test
  public void listsOuterClassesInOrder() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "public class A {",
            "  class B {",
            "    class C {",
            "      class D {",
            "      }",
            "    }",
            "  }",
            "}")
        .expectInnerClassesTable(
            "com.example.buck.A.B.C.D",
            "com/example/buck/A$B",
            "com/example/buck/A$B$C",
            "com/example/buck/A$B$C$D");
  }

  @Test
  public void listsInnerClassesInReverseOrder() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "public class A {",
            "  class Inner1 { }",
            "  class Inner2 { }",
            "  class Inner3 { }",
            "  class Inner4 { }",
            "}")
        .expectInnerClassesTable(
            "com.example.buck.A",
            "com/example/buck/A$Inner4",
            "com/example/buck/A$Inner3",
            "com/example/buck/A$Inner2",
            "com/example/buck/A$Inner1");
  }

  @Test
  public void listsOuterThenInnerThenReferences() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "public class A {",
            "  class B {",
            "    class C {",
            "      TopLevel.StaticMember field;",
            "      void foo(TopLevel.MemberInterface2 param) { }",
            "      class D { }",
            "      TopLevel.MemberInterface field2;",
            "      void foo(TopLevel.StaticMember.StaticMemberMember param) { }",
            "    }",
            "  }",
            "}")
        .expectInnerClassesTable(
            "com.example.buck.A.B.C",
            "com/example/buck/A$B",
            "com/example/buck/A$B$C",
            "com/example/buck/A$B$C$D",
            "com/example/buck/TopLevel$MemberInterface",
            "com/example/buck/TopLevel$MemberInterface2",
            "com/example/buck/TopLevel$StaticMember",
            "com/example/buck/TopLevel$StaticMember$StaticMemberMember");
  }
  // endregion

  // Edge case tests that don't belong anywhere else.
  // region EdgeCasesTests
  @Test
  public void doesNotListInnersOfInnersIfNotReferenced() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "public class A {",
            "  class B {",
            "    class C {",
            "      TopLevel.StaticMember field;",
            "      class D { }",
            "    }",
            "  }",
            "}")
        .expectInnerClassesTable(
            "com.example.buck.A.B", "com/example/buck/A$B", "com/example/buck/A$B$C");
  }

  @Test
  public void listsInnersOfInnersIfReferenced() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "public class A {",
            "  class B {",
            "    C.D field;",
            "    class C {",
            "      TopLevel.StaticMember field;",
            "      class D { }",
            "    }",
            "  }",
            "}")
        .expectInnerClassesTable(
            "com.example.buck.A.B",
            "com/example/buck/A$B",
            "com/example/buck/A$B$C",
            "com/example/buck/A$B$C$D");
  }

  @Test
  public void includesAllEnclosingClassesForAReferencedType() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "import com.example.buck.TopLevel.StaticMember.Inner.*;",
            "public class A {",
            "  Innermost field;",
            "}")
        .expectInnerClassesTable(
            "com.example.buck.A",
            "com/example/buck/TopLevel$StaticMember",
            "com/example/buck/TopLevel$StaticMember$Inner",
            "com/example/buck/TopLevel$StaticMember$Inner$Innermost");
  }
  // endregion

  // Tests that inner class references are found even if they are deeply inside some compound type
  // expression.
  // region CompoundTypeTests
  @Test
  public void findsInnerClassReferencesInArrays() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "public class A {",
            "  TopLevel.StaticMember[] field;",
            "}")
        .expectInnerClassesTable("com.example.buck.A", "com/example/buck/TopLevel$StaticMember");
  }

  @Test
  public void findsInnerClassReferencesInTypeArgs() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "import java.util.List;",
            "public class A {",
            "  List<TopLevel.StaticMember> field;",
            "}")
        .expectInnerClassesTable("com.example.buck.A", "com/example/buck/TopLevel$StaticMember");
  }

  @Test
  public void findsInnerClassReferencesInExtendsWildcards() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "import java.util.List;",
            "public class A {",
            "  List<? extends TopLevel.StaticMember> field;",
            "}")
        .expectInnerClassesTable("com.example.buck.A", "com/example/buck/TopLevel$StaticMember");
  }

  @Test
  public void findsInnerClassReferencesInSuperWildcards() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "import java.util.List;",
            "public class A {",
            "  List<? super TopLevel.StaticMember> field;",
            "}")
        .expectInnerClassesTable("com.example.buck.A", "com/example/buck/TopLevel$StaticMember");
  }
  // endregion

  // Tests that inner class references are found in package declarations.
  // region PackageTests
  @Test
  public void findsInnerClassReferencesInPackageAnnotations() throws IOException {
    tester
        .setSourceFile(
            "package-info.java", "@TopLevel.MemberAnnotation", "package com.example.buck;")
        .expectInnerClassesTable(
            "com.example.buck.package-info", "com/example/buck/TopLevel$MemberAnnotation");
  }
  // endregion

  // Tests that inner class references are found in various parts of a class declaration (not
  // including members)
  // region ClassTests
  @Test
  public void findsInnerClassReferencesInClassAnnotations() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "@TopLevel.MemberAnnotation",
            "public class A { }")
        .expectInnerClassesTable(
            "com.example.buck.A", "com/example/buck/TopLevel$MemberAnnotation");
  }

  @Test
  public void findsInnerClassReferencesInClassTypeParameterAnnotations() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "public class A<T, U, @TopLevel.MemberAnnotation V> { }")
        .expectInnerClassesTable(
            "com.example.buck.A", "com/example/buck/TopLevel$MemberAnnotation");
  }

  @Test
  public void findsInnerClassReferencesInClassTypeParameters() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "public class A<T extends TopLevel.MemberInterface & TopLevel.MemberInterface2, U, V extends TopLevel.StaticMember> { }")
        .expectInnerClassesTable(
            "com.example.buck.A",
            "com/example/buck/TopLevel$MemberInterface",
            "com/example/buck/TopLevel$MemberInterface2",
            "com/example/buck/TopLevel$StaticMember");
  }

  @Test
  public void findsInnerClassReferencesInSuperclass() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "public class A extends TopLevel.StaticMember { }")
        .expectInnerClassesTable("com.example.buck.A", "com/example/buck/TopLevel$StaticMember");
  }

  @Test
  public void findsInnerClassReferencesInInterfaces() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "public class A implements TopLevel.MemberInterface, TopLevel.MemberInterface2 { }")
        .expectInnerClassesTable(
            "com.example.buck.A",
            "com/example/buck/TopLevel$MemberInterface",
            "com/example/buck/TopLevel$MemberInterface2");
  }
  // endregion

  // Tests that inner class references are found in various parts of a method declaration (not
  // including parameters)
  // region MethodTests
  @Test
  public void findsInnerClassReferencesInMethodAnnotations() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "public class A {",
            "  @TopLevel.MemberAnnotation",
            "  void foo() { }",
            "}")
        .expectInnerClassesTable(
            "com.example.buck.A", "com/example/buck/TopLevel$MemberAnnotation");
  }

  @Test
  public void findsInnerClassReferencesInMethodReturnType() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "public class A {",
            "  TopLevel.StaticMember foo() {",
            "    return null;",
            "  }",
            "}")
        .expectInnerClassesTable("com.example.buck.A", "com/example/buck/TopLevel$StaticMember");
  }

  @Test
  public void findsInnerClassReferencesInMethodThrows() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "public class A {",
            "  void foo() throws TopLevel.MemberException { }",
            "}")
        .expectInnerClassesTable("com.example.buck.A", "com/example/buck/TopLevel$MemberException");
  }

  @Test
  public void findsInnerClassReferencesInMethodDefaultValue() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "public @interface A {",
            "  Anno value() default @Anno(@TopLevel.MemberAnnotation);",
            "}",
            "@interface Anno {",
            "  TopLevel.MemberAnnotation value();",
            "}")
        .expectInnerClassesTable(
            "com.example.buck.A", "com/example/buck/TopLevel$MemberAnnotation");
  }

  @Test
  public void findsInnerClassReferencesInMethodTypeParameterAnnotations() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "public class A {",
            "  <T, U, @TopLevel.MemberAnnotation V> void foo(T t, U u, V v) { }",
            "}")
        .expectInnerClassesTable(
            "com.example.buck.A", "com/example/buck/TopLevel$MemberAnnotation");
  }

  @Test
  public void findsInnerClassReferencesInMethodTypeParameters() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "public class A {",
            "  <T extends TopLevel.MemberInterface & TopLevel.MemberInterface2, U, V extends TopLevel.StaticMember> void foo(T t, U u, V v) { }",
            "}")
        .expectInnerClassesTable(
            "com.example.buck.A",
            "com/example/buck/TopLevel$MemberInterface",
            "com/example/buck/TopLevel$MemberInterface2",
            "com/example/buck/TopLevel$StaticMember");
  }
  // endregion

  // Tests that inner class references are found in various parts of a field or parameter
  // declaration.
  // region FieldAndParameterTests
  @Test
  public void findsInnerClassReferencesInFieldAnnotations() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "public class A {",
            "  @TopLevel.MemberAnnotation",
            "  int field;",
            "}")
        .expectInnerClassesTable(
            "com.example.buck.A", "com/example/buck/TopLevel$MemberAnnotation");
  }

  @Test
  public void findsInnerClassReferencesInFieldType() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "public class A {",
            "  TopLevel.StaticMember field;",
            "}")
        .expectInnerClassesTable("com.example.buck.A", "com/example/buck/TopLevel$StaticMember");
  }

  @Test
  public void findsInnerClassReferencesInParameterAnnotations() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "public class A {",
            "  void foo(int param1, int param2, @TopLevel.MemberAnnotation int param3) { }",
            "}")
        .expectInnerClassesTable(
            "com.example.buck.A", "com/example/buck/TopLevel$MemberAnnotation");
  }

  @Test
  public void findsInnerClassReferencesInParameterType() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "public class A {",
            "  void foo(TopLevel.StaticMember param1, TopLevel.MemberInterface param2, TopLevel.MemberInterface2 param3) {}",
            "}")
        .expectInnerClassesTable(
            "com.example.buck.A",
            "com/example/buck/TopLevel$MemberInterface",
            "com/example/buck/TopLevel$MemberInterface2",
            "com/example/buck/TopLevel$StaticMember");
  }
  // endregion

  // Tests that inner class references are found in different types of annotation values.
  // region AnnotationValueTests
  @Test
  public void findsInnerClassReferencesInAnnotationTypeValues() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "@Anno(TopLevel.StaticMember.class)",
            "public class A {",
            "}",
            "@interface Anno {",
            "  Class<?> value();",
            "}")
        .expectInnerClassesTable("com.example.buck.A", "com/example/buck/TopLevel$StaticMember");
  }

  @Test
  public void findsInnerClassReferencesInAnnotationEnumValues() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "@Anno(TopLevel.StaticMember.StaticMemberMember.InnerEnum.Value)",
            "public class A {",
            "}",
            "@interface Anno {",
            "  TopLevel.StaticMember.StaticMemberMember.InnerEnum value();",
            "}")
        .expectInnerClassesTable(
            "com.example.buck.A",
            "com/example/buck/TopLevel$StaticMember",
            "com/example/buck/TopLevel$StaticMember$StaticMemberMember",
            "com/example/buck/TopLevel$StaticMember$StaticMemberMember$InnerEnum");
  }

  @Test
  public void findsInnerClassReferencesInAnnotationAnnotationValues() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "@B.Anno(@TopLevel.MemberAnnotation)",
            "public class A {",
            "}",
            "class B {",
            "  @interface Anno {",
            "    TopLevel.MemberAnnotation value();",
            "  }",
            "}")
        .expectInnerClassesTable(
            "com.example.buck.A",
            "com/example/buck/B$Anno",
            "com/example/buck/TopLevel$MemberAnnotation");
  }

  @Test
  public void findsInnerClassReferencesInNestedAnnotationAnnotationValues() throws IOException {
    tester
        .setSourceFile(
            "A.java",
            "package com.example.buck;",
            "import com.example.buck.TopLevel.StaticMember.StaticMemberMember.InnerEnum;",
            "@B.Anno(@B.Anno2({InnerEnum.Value}))",
            "public class A {",
            "}",
            "class B {",
            "  @interface Anno {",
            "    Anno2 value();",
            "  }",
            "  @interface Anno2 {",
            "    InnerEnum[] value();",
            "  }",
            "}")
        .expectInnerClassesTable(
            "com.example.buck.A",
            "com/example/buck/B$Anno",
            "com/example/buck/B$Anno2",
            "com/example/buck/TopLevel$StaticMember",
            "com/example/buck/TopLevel$StaticMember$StaticMemberMember",
            "com/example/buck/TopLevel$StaticMember$StaticMemberMember$InnerEnum");
  }
  // endregion

  // region Infrastructure
  private static class Tester extends ExternalResource {
    private final TestCompiler compiler = new TestCompiler();

    public Tester setSourceFile(String name, String... lines) throws IOException {
      compiler.addSourceFileContents(name, String.join("\n", lines));
      return this;
    }

    public Tester expectInnerClassesTable(String className, String... innerClassesEntries)
        throws IOException {
      compiler.addClasspathFileContents(
          "TopLevel.java",
          "package com.example.buck;",
          "import java.lang.annotation.*;",
          "public class TopLevel {",
          "  public static class StaticMember {",
          "    public class Inner {",
          "      public class Innermost {",
          "      }",
          "    }",
          "    public static class StaticMemberMember {",
          "      public enum InnerEnum { Value };",
          "    }",
          "  }",
          "  public interface MemberInterface {",
          "    interface MemberMemberInterface {",
          "    }",
          "  }",
          "  public interface MemberInterface2 { }",
          "  @Target({ElementType.PACKAGE, ElementType.TYPE, ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.TYPE_PARAMETER})",
          "  public @interface MemberAnnotation { }",
          "  public class MemberException extends Exception { }",
          "}");
      compiler.setProcessors(Collections.emptyList());
      compiler.enter();
      assertEquals(Collections.emptyList(), compiler.getDiagnosticMessages());

      ElementsExtended elements = compiler.getElements();
      Element topElement =
          className.endsWith("package-info")
              ? elements.getPackageElement(className.replace(".package-info", ""))
              : elements.getTypeElement(className);

      DescriptorFactory descriptorFactory = new DescriptorFactory(elements);
      AccessFlags accessFlags = new AccessFlags(elements);
      InnerClassesTable innerClassesTable =
          new InnerClassesTable(descriptorFactory, accessFlags, topElement);

      ClassNode classNode = new ClassNode(Opcodes.ASM6);
      innerClassesTable.reportInnerClassReferences(classNode);

      assertEquals(
          Arrays.asList(innerClassesEntries),
          classNode
              .innerClasses
              .stream()
              .map(innerClass -> innerClass.name)
              .collect(Collectors.toList()));
      return this;
    }

    @Override
    protected void before() throws Throwable {
      super.before();
      compiler.init();
    }

    @Override
    protected void after() {
      super.after();
      compiler.close();
    }
  }
  // endregion
}
