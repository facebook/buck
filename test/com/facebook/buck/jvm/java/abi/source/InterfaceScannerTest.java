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

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;

import com.facebook.buck.jvm.java.plugin.adapter.BuckJavacTask;
import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTest;
import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTestRunner;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import javax.lang.model.element.QualifiedNameable;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiTestRunner.class)
public class InterfaceScannerTest extends CompilerTreeApiTest {
  private List<String> typeReferences;
  private List<String> constantReferences;
  private List<TypeElement> importedTypes;
  private List<TypeElement> declaredTypes;
  private List<QualifiedNameable> starImportedElements;
  private Map<String, TypeElement> staticImportOwners;
  private List<TypeElement> staticStarImports;

  @Test
  public void testFindsVariableTypeReference() throws IOException {
    findTypeReferences("class Foo {", "  String s;", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(createSymbolicReference("java.lang.String", 2, 3)));
  }

  @Test
  public void testFindsTypeReferencesInNestedTypes() throws IOException {
    findTypeReferences("class Foo {", "  class Bar {", "    String s;", "  }", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(createSymbolicReference("java.lang.String", 3, 5)));
  }

  @Test
  public void testFindsTypeReferencesInPrivateNestedTypes() throws IOException {
    findTypeReferences("class Foo {", "  private class Bar {", "    String s;", "  }", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(createSymbolicReference("java.lang.String", 3, 5)));
  }

  @Test
  public void testFindsTypeArgTypeReference() throws IOException {
    findTypeReferences("import java.util.Map;", "class Foo {", "  Map<String, Integer> s;", "}");

    assertThat(
        typeReferences,
        Matchers.contains(
            createSymbolicReference("java.util.Map", 3, 3),
            createSymbolicReference("java.lang.String", 3, 7),
            createSymbolicReference("java.lang.Integer", 3, 15)));
  }

  @Test
  public void testFindsWildcardBoundsTypeReference() throws IOException {
    findTypeReferences(
        "import java.util.Map;",
        "class Foo {",
        "  Map<? extends CharSequence, ? super Integer> s;",
        "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.util.Map", 3, 3),
            createSymbolicReference("java.lang.CharSequence", 3, 17),
            createSymbolicReference("java.lang.Integer", 3, 39)));
  }

  @Test
  public void testFindsArrayElementTypeReference() throws IOException {
    findTypeReferences("class Foo {", "  String[][] s;", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(createSymbolicReference("java.lang.String", 2, 3)));
  }

  @Test
  public void testFindsFullyQualifiedTypeReference() throws IOException {
    findTypeReferences("class Foo {", "  java.lang.String s;", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(createSymbolicReference("java.lang.String", 2, 3)));
  }

  @Test
  public void testFindsTypeParameterBoundTypeReferences() throws IOException {
    findTypeReferences("class Foo<T extends Runnable & CharSequence> { }");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.Runnable", 1, 21),
            createSymbolicReference("java.lang.CharSequence", 1, 32)));
  }

  @Test
  public void testReportsLeafmostTypeReference() throws IOException {
    findTypeReferences("import java.util.Map;", "class Foo {", "  Map.Entry entry;", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(createSymbolicReference("java.util.Map.Entry", 3, 3)));
  }

  @Test
  public void testIgnoresTypeVariableTypeReferences() throws IOException {
    findTypeReferences("class Foo<T extends Runnable & CharSequence> {", "  T t;", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.Runnable", 1, 21),
            createSymbolicReference("java.lang.CharSequence", 1, 32)));
  }

  @Test
  public void testIgnoresPrimitiveTypeReferences() throws IOException {
    findTypeReferences("class Foo {", "  int i;", "}");

    assertThat(typeReferences, Matchers.empty());
  }

  @Test
  public void testIgnoresNullTypeReferences() throws IOException {
    findTypeReferences("class Foo<T> { }");

    assertThat(typeReferences, Matchers.empty());
  }

  @Test
  public void testIgnoresVoidTypeReferences() throws IOException {
    findTypeReferences("class Foo {", "  void method() {  };", "}");

    assertThat(typeReferences, Matchers.empty());
  }

  @Test
  public void testFindsReturnTypeReference() throws IOException {
    findTypeReferences("class Foo {", "  String method() { return null; }", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(createSymbolicReference("java.lang.String", 2, 3)));
  }

  @Test
  public void testFindsMethodParameterTypeReferences() throws IOException {
    findTypeReferences("class Foo {", "  void method(String s, Integer i) { }", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.String", 2, 15),
            createSymbolicReference("java.lang.Integer", 2, 25)));
  }

  @Test
  public void testFindsMethodTypeParameterTypeReferences() throws IOException {
    findTypeReferences("class Foo {", "  <T extends CharSequence> void method() { }", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(createSymbolicReference("java.lang.CharSequence", 2, 14)));
  }

  @Test
  public void testFindsSuperclassTypeReferences() throws IOException {
    findTypeReferences("abstract class Foo extends java.util.ArrayList { } ");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(createSymbolicReference("java.util.ArrayList", 1, 28)));
  }

  @Test
  public void testFindsInterfaceTypeReferences() throws IOException {
    findTypeReferences("abstract class Foo implements Runnable, CharSequence { } ");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.Runnable", 1, 31),
            createSymbolicReference("java.lang.CharSequence", 1, 41)));
  }

  @Test
  public void testFindsAnnotationTypeReferencesOnMethods() throws IOException {
    findTypeReferences(
        "class Foo implements Runnable {", "  @Override", "  public void run() { }", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.Runnable", 1, 22),
            createSymbolicReference("java.lang.Override", 2, 4)));
  }

  @Test
  public void testFindsAnnotationTypeReferencesOnFields() throws IOException {
    findTypeReferences("class Foo {", "  @SuppressWarnings(\"foo\")", "  String s;", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.SuppressWarnings", 2, 4),
            createSymbolicReference("java.lang.String", 3, 3)));
  }

  @Test
  public void testFindsAnnotationTypeReferencesOnTypeParameters() throws IOException {
    findTypeReferences(
        "import java.lang.annotation.*;",
        "class Foo<@Anno T> { }",
        "@Target(ElementType.TYPE_PARAMETER)",
        "@interface Anno { }");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("Anno", 2, 12),
            createSymbolicReference("java.lang.annotation.Target", 3, 2),
            createSymbolicReference("java.lang.annotation.ElementType", 3, 9)));
  }

  @Test
  public void testFindsAnnotationTypeReferencesOnTypes() throws IOException {
    findTypeReferences(
        "import java.lang.annotation.*;",
        "abstract class Foo implements @Anno CharSequence { }",
        "@Target(ElementType.TYPE_USE)",
        "@interface Anno { }");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("Anno", 2, 32),
            createSymbolicReference("java.lang.CharSequence", 2, 37),
            createSymbolicReference("java.lang.annotation.Target", 3, 2),
            createSymbolicReference("java.lang.annotation.ElementType", 3, 9)));
  }

  @Test
  public void testFindsAnnotationTypeReferencesOnClasses() throws IOException {
    findTypeReferences("import java.lang.annotation.*;", "@Documented", "@interface Foo { }");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.annotation.Documented", 2, 2)));
  }

  @Test
  public void testIgnoresMethodBodies() throws IOException {
    findTypeReferences("class Foo {", "  void method() {", "    String s;", "  }", "}");

    assertThat(typeReferences, Matchers.empty());
  }

  @Test
  public void testIgnoresAnonymousClasses() throws IOException {
    findTypeReferences(
        "class Foo {",
        "  Runnable r = new Runnable() {",
        "    String s;",
        "    public void run() { }",
        "  };",
        "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(createSymbolicReference("java.lang.Runnable", 2, 3)));
  }

  @Test
  public void testIgnoresPrivateMethods() throws IOException {
    findTypeReferences("class Foo {", "  private String method() { return null; }", "}");

    assertThat(typeReferences, Matchers.empty());
  }

  @Test
  public void testIgnoresPrivateFields() throws IOException {
    findTypeReferences("class Foo {", "  private String s;", "}");

    assertThat(typeReferences, Matchers.empty());
  }

  @Test
  public void testIgnoresReferencesInInitializers() throws IOException {
    findTypeReferences(
        "class Foo {",
        "  public static final String s = Constants.CONSTANT;",
        "}",
        "class Constants {",
        "  public static final String CONSTANT = \"Hello\";",
        "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.String", 2, 23),
            createSymbolicReference("java.lang.String", 5, 23)));
  }

  @Test
  public void testIgnoresReferencesToOtherConstantMembers() throws IOException {
    findTypeReferences(
        "class Foo {",
        "  public static final String CONSTANT = \"Hello\";",
        "  public static final String s = CONSTANT;",
        "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.String", 3, 23),
            createSymbolicReference("java.lang.String", 2, 23)));
  }

  @Test
  public void testIgnoresReferencesInNonStaticConstants() throws IOException {
    findTypeReferences(
        "class Foo {",
        "  public final String s = Constants.CONSTANT;",
        "}",
        "class Constants {",
        "  public static final String CONSTANT = \"Hello\";",
        "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.String", 2, 16),
            createSymbolicReference("java.lang.String", 5, 23)));
  }

  @Test
  public void testIgnoresReferencesInMethodDefaultValuePrimitiveConstants() throws IOException {
    findTypeReferences(
        "@interface Foo {",
        "  String value() default Constants.CONSTANT;",
        "}",
        "class Constants {",
        "  public static final String CONSTANT = \"Hello\";",
        "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.String", 2, 3),
            createSymbolicReference("java.lang.String", 5, 23)));
  }

  @Test
  public void testFindsReferencesInMethodDefaultValueClassLiterals() throws IOException {
    findTypeReferences(
        "@interface Foo {",
        "  Class value() default Constants.class;",
        "}",
        "class Constants {",
        "  public static final String CONSTANT = \"Hello\";",
        "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.Class", 2, 3),
            createSymbolicReference("Constants", 2, 25),
            createSymbolicReference("java.lang.String", 5, 23)));
  }

  @Test
  public void testFindsReferencesInMethodDefaultValueEnumConstants() throws IOException {
    findTypeReferences(
        "@interface Foo {",
        "  Constants value() default Constants.CONSTANT;",
        "}",
        "enum Constants {",
        "  CONSTANT;",
        "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("Constants", 2, 3),
            createSymbolicReference("Constants", 2, 29),
            createSymbolicReference("Constants", 5, 3)));
  }

  @Test
  public void testIgnoresReferencesInAnnotationValuePrimitiveConstants() throws IOException {
    findTypeReferences(
        "@SuppressWarnings(Constants.CONSTANT)",
        "class Foo {",
        "}",
        "class Constants {",
        "  public static final String CONSTANT = \"Hello\";",
        "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.SuppressWarnings", 1, 2),
            createSymbolicReference("java.lang.String", 5, 23)));
  }

  @Test
  public void testFindsReferencesInAnnotationValueEnumConstants() throws IOException {
    findTypeReferences(
        "import java.lang.annotation.*;", "@Target(ElementType.TYPE)", "@interface Foo {", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.annotation.Target", 2, 2),
            createSymbolicReference("java.lang.annotation.ElementType", 2, 9)));
  }

  @Test
  public void testFindsReferencesInAnnotationValueClassLiterals() throws IOException {
    findTypeReferences(
        "@Anno(Bar.class)",
        "class Foo {",
        "}",
        "class Bar {",
        "}",
        "@interface Anno {",
        "  Class<?> value();",
        " }");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("Anno", 1, 2),
            createSymbolicReference("Bar", 1, 7),
            createSymbolicReference("java.lang.Class", 7, 3)));
  }

  @Test
  public void testFindsReferencesInAnnotationValuesThatAreAnnotations() throws IOException {
    findTypeReferences(
        "@Bar(@Baz)",
        "class Foo {",
        "}",
        "@interface Bar {",
        "  Baz value();",
        "}",
        "@interface Baz {}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("Bar", 1, 2),
            createSymbolicReference("Baz", 1, 7),
            createSymbolicReference("Baz", 5, 3)));
  }

  @Test
  public void testFindsReferencesInAnnotationValuesThatAreAnnotationArrays() throws IOException {
    findTypeReferences(
        "@Bar({@Baz, @Baz})",
        "class Foo {",
        "}",
        "@interface Bar {",
        "  Baz[] value();",
        "}",
        "@interface Baz {}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("Bar", 1, 2),
            createSymbolicReference("Baz", 1, 8),
            createSymbolicReference("Baz", 1, 14),
            createSymbolicReference("Baz", 5, 3)));
  }

  @Test
  public void testFindsImportedTypes() throws IOException {
    findTypeReferences("import java.util.List;", "class Foo { }");

    assertThat(
        importedTypes, Matchers.containsInAnyOrder(elements.getTypeElement("java.util.List")));
  }

  @Test
  public void testFindsStarImportedPackages() throws IOException {
    findTypeReferences("import java.util.*;", "class Foo { }");

    assertThat(
        starImportedElements, Matchers.containsInAnyOrder(elements.getPackageElement("java.util")));
  }

  @Test
  public void testFindsStarImportedTypes() throws IOException {
    findTypeReferences("import java.util.HashMap.*;", "class Foo { }");

    assertThat(
        starImportedElements,
        Matchers.containsInAnyOrder(elements.getTypeElement("java.util.HashMap")));
  }

  @Test
  public void testFindsStaticImportsOfNestedTypes() throws IOException {
    findTypeReferences("import static java.text.DateFormat.Field;", "class Foo { }");

    assertSame(
        staticImportOwners.get("Field"),
        elements.getTypeElement("java.text.DateFormat.Field").getEnclosingElement());
  }

  @Test
  public void testFindsStaticOnDemandImports() throws IOException {
    findTypeReferences("import static java.text.DateFormat.*;", "class Foo { }");

    assertThat(
        staticStarImports,
        Matchers.containsInAnyOrder(elements.getTypeElement("java.text.DateFormat")));
  }

  @Test
  public void testIgnoresStaticImportsOfNonStaticTypes() throws IOException {
    withClasspath(
        ImmutableMap.of(
            "com/facebook/bar/Bar.java",
            Joiner.on('\n')
                .join(
                    "package com.facebook.bar;",
                    "public class Bar {",
                    "  public class Inner { }",
                    "  public static void Inner() {}",
                    "}")));

    findTypeReferences("import static com.facebook.bar.Bar.Inner;", "class Foo { }");

    assertThat(importedTypes, Matchers.empty());
  }

  @Test
  public void testDoesNotFindInaccessibleStaticImportsOfNestedTypes() throws IOException {
    withClasspath(
        ImmutableMap.of(
            "com/facebook/bar/Bar.java",
            Joiner.on('\n')
                .join(
                    "package com.facebook.bar;",
                    "public class Bar {",
                    "  public static void Inner() {}",
                    "private static class Inner { }",
                    "}")));
    findTypeReferences("import static com.facebook.bar.Bar.Inner;", "class Foo { }");

    assertThat(importedTypes, Matchers.empty());
  }

  @Test
  public void testStaticImportsOfMissingNestedTypesDoNotCompile() throws IOException {
    findTypeReferencesErrorsOK("import static java.text.DateFormat.Missing;", "class Foo { }");

    assertError(
        "Foo.java:1: error: cannot find symbol\n"
            + "import static java.text.DateFormat.Missing;\n"
            + "^\n"
            + "  symbol:   static Missing\n"
            + "  location: class");
    assertThat(importedTypes, Matchers.empty());
  }

  @Test
  public void testIgnoresStaticImportsOfMethods() throws IOException {
    findTypeReferences("import static java.util.Collections.*;", "class Foo { }");

    assertThat(importedTypes, Matchers.empty());
  }

  @Test
  public void testDoesNotCrashOnSimpleNamedPackage() throws IOException {
    findTypeReferencesErrorsOK("package example;", "class Foo { }");
  }

  @Test
  public void testFindsSimpleNameConstants() throws IOException {
    withClasspath(
        ImmutableMap.of(
            "com/facebook/foo/Constants.java",
            Joiner.on('\n')
                .join(
                    "package com.facebook.foo;",
                    "class Constants {",
                    "  protected static final int CONSTANT = 3;",
                    "}")));
    findTypeReferences(
        "package com.facebook.foo;",
        "class Foo extends Constants {",
        "  public static final int CONSTANT2 = CONSTANT;",
        "}");

    assertThat(
        constantReferences, Matchers.containsInAnyOrder(createSymbolicReference("3", 3, 39)));
  }

  @Test
  public void testFindsQualifiedNameConstants() throws IOException {
    withClasspath(
        ImmutableMap.of(
            "com/facebook/foo/Constants.java",
            Joiner.on('\n')
                .join(
                    "package com.facebook.foo;",
                    "public class Constants {",
                    "  public static final int CONSTANT = 3;",
                    "}")));
    findTypeReferences(
        "class Foo {",
        "  public static final int CONSTANT2 = com.facebook.foo.Constants.CONSTANT;",
        "}");

    assertThat(
        constantReferences, Matchers.containsInAnyOrder(createSymbolicReference("3", 2, 39)));
  }

  @Test
  public void testFindsEnumConstants() throws IOException {
    findTypeReferences(
        "@interface Foo {",
        "  Constants value() default Constants.CONSTANT;",
        "}",
        "enum Constants {",
        "  CONSTANT;",
        "}");

    assertThat(
        constantReferences,
        Matchers.containsInAnyOrder(createSymbolicReference("CONSTANT", 2, 29)));
  }

  @Test
  public void testFindsSimpleNameInstanceConstants() throws IOException {
    withClasspath(
        ImmutableMap.of(
            "com/facebook/foo/Constants.java",
            Joiner.on('\n')
                .join(
                    "package com.facebook.foo;",
                    "class Constants {",
                    "  protected final int CONSTANT = 3;",
                    "}")));
    findTypeReferences(
        "package com.facebook.foo;",
        "class Foo extends Constants {",
        "  public final int CONSTANT2 = CONSTANT;",
        "}");

    assertThat(
        constantReferences, Matchers.containsInAnyOrder(createSymbolicReference("3", 3, 32)));
  }

  @Test
  public void testIgnoresReferencesToNonexistentTypes() throws IOException {
    findTypeReferencesErrorsOK(
        "package com.facebook.foo;",
        "class Foo {",
        "  protected Bar getBar() { return null; };",
        "}");

    assertThat(typeReferences, Matchers.empty());
  }

  @Test
  public void testFindsAnnotationTypeDefinitions() throws IOException {
    findTypeReferences("@interface Foo {", "  @interface Inner { }", "}", "@interface Bar { }");

    assertThat(
        declaredTypes
            .stream()
            .map(TypeElement::getQualifiedName)
            .map(Name::toString)
            .collect(Collectors.toList()),
        Matchers.contains("Foo", "Foo.Inner", "Bar"));
  }

  @Test
  public void testFindsClassDefinitions() throws IOException {
    findTypeReferences("public class Foo {", "  private class Inner { }", "}", "class Bar { }");

    assertThat(
        declaredTypes
            .stream()
            .map(TypeElement::getQualifiedName)
            .map(Name::toString)
            .collect(Collectors.toList()),
        Matchers.contains("Foo", "Foo.Inner", "Bar"));
  }

  private void findTypeReferences(String... sourceLines) throws IOException {
    findTypeReferences(sourceLines, false);
  }

  private void findTypeReferencesErrorsOK(String... sourceLines) throws IOException {
    findTypeReferences(sourceLines, true);
  }

  private void findTypeReferences(String[] sourceLines, boolean errorsOK) throws IOException {
    constantReferences = new ArrayList<>();
    typeReferences = new ArrayList<>();
    importedTypes = new ArrayList<>();
    starImportedElements = new ArrayList<>();
    declaredTypes = new ArrayList<>();
    staticImportOwners = new HashMap<>();
    staticStarImports = new ArrayList<>();

    testCompiler.setAllowCompilationErrors(errorsOK);
    compile(
        ImmutableMap.of("Foo.java", Joiner.on('\n').join(sourceLines)),
        // ErrorType's get nulled out when the task returns, so we have to get a call back during
        // the run so that we can still look at them.
        new TaskListenerFactory() {
          @Override
          public TaskListener newTaskListener(BuckJavacTask task) {
            return new PostEnterCallback() {
              @Override
              protected void enterComplete(List<CompilationUnitTree> compilationUnits) {
                FinderListener listener = new FinderListener();
                InterfaceScanner finder = new InterfaceScanner(trees);
                for (CompilationUnitTree compilationUnit : compilationUnits) {
                  finder.findReferences(compilationUnit, listener);
                }
              }
            };
          }
        });

    if (!errorsOK) {
      assertNoErrors();
    }
  }

  private String createSymbolicReference(String referent, TreePath treePath) {
    try {
      long position =
          trees
              .getSourcePositions()
              .getStartPosition(treePath.getCompilationUnit(), treePath.getLeaf());

      CharSequence content = treePath.getCompilationUnit().getSourceFile().getCharContent(true);

      int line = 1;
      int column = 1;
      for (long i = 0; i < position; i++, column++) {
        if (content.charAt((int) i) == '\n') {
          line += 1;
          column = 0;
        }
      }

      return createSymbolicReference(referent, line, column);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private String createSymbolicReference(String referent, int line, int column) {
    return String.format("%d, %d: %s", line, column, referent);
  }

  private class FinderListener implements InterfaceScanner.Listener {

    @Override
    public void onTypeDeclared(TypeElement type, TreePath path) {
      declaredTypes.add(type);
    }

    @Override
    public void onImport(
        boolean isStatic,
        boolean isStarImport,
        TreePath leafmostElementPath,
        QualifiedNameable leafmostElement,
        Name memberName) {
      if (!isStatic) {
        if (isStarImport) {
          starImportedElements.add(leafmostElement);
        } else {
          importedTypes.add((TypeElement) leafmostElement);
        }
      } else if (!isStarImport) {
        staticImportOwners.put(memberName.toString(), (TypeElement) leafmostElement);
      } else {
        staticStarImports.add((TypeElement) leafmostElement);
      }
    }

    @Override
    public void onTypeReferenceFound(TypeElement type, TreePath path, Element referencingElement) {
      typeReferences.add(createSymbolicReference(type.getQualifiedName().toString(), path));
    }

    @Override
    public void onConstantReferenceFound(
        VariableElement constant, TreePath path, Element referencingElement) {
      String value;
      if (constant.getKind() == ElementKind.ENUM_CONSTANT) {
        value = constant.getSimpleName().toString();
      } else {
        value = constant.getConstantValue().toString();
      }

      constantReferences.add(createSymbolicReference(value, path));
    }
  }
}
