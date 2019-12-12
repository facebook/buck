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

package com.facebook.buck.jvm.java.lang.model;

import static org.junit.Assert.assertThat;

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTest;
import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTestRunner;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiTestRunner.class)
public class BridgeMethodsTest extends CompilerTreeApiTest {
  @Test
  public void testFindsAccessibilityBridgeToPackageType() throws IOException {
    compile(
        ImmutableMap.of(
            "Super.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "class Super {",
                    "  public void publicMethod() { }",
                    "}"),
            "Subclass.java",
            Joiner.on('\n')
                .join("package com.example.buck;", "public class Subclass extends Super { }")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    TypeElement superclass = elements.getTypeElement("com.example.buck.Super");
    assertNeededBridges(
        subclass,
        new BridgeMethod(
            getMethod(superclass, "publicMethod"), getMethod(superclass, "publicMethod")));
  }

  @Test
  public void testFindsAccessibilityBridgeToPrivateType() throws IOException {
    compile(
        ImmutableMap.of(
            "Subclass.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public class Subclass {",
                    "  private class InnerSuper {",
                    "    public void publicMethod() {}",
                    "}",
                    "  public class Inner extends InnerSuper { }",
                    "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass.Inner");
    TypeElement superclass = elements.getTypeElement("com.example.buck.Subclass.InnerSuper");
    assertNeededBridges(
        subclass,
        new BridgeMethod(
            getMethod(superclass, "publicMethod"), getMethod(superclass, "publicMethod")));
  }

  @Test
  public void testNoAccessibilityBridgesInNonPublicSubclass() throws IOException {
    compile(
        ImmutableMap.of(
            "Super.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "class Super {",
                    "  public void publicMethod() { }",
                    "}"),
            "Subclass.java",
            Joiner.on('\n')
                .join("package com.example.buck;", "class Subclass extends Super {", "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    assertNoNeededBridges(subclass);
  }

  @Test
  public void testFindsAccessibilityBridgesInSuperSuper() throws IOException {
    compile(
        ImmutableMap.of(
            "SuperSuper.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "class SuperSuper {",
                    "  public void publicMethod() { }",
                    "}"),
            "Super.java",
            Joiner.on('\n')
                .join("package com.example.buck;", "class Super extends SuperSuper {", "}"),
            "Subclass.java",
            Joiner.on('\n')
                .join("package com.example.buck;", "public class Subclass extends Super {", "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    TypeElement superclass = elements.getTypeElement("com.example.buck.SuperSuper");
    assertNeededBridges(
        subclass,
        new BridgeMethod(
            getMethod(superclass, "publicMethod"), getMethod(superclass, "publicMethod")));
  }

  @Test
  public void testNoAccessibilityBridgesWhenSuperclassShouldHaveThem() throws IOException {
    compile(
        ImmutableMap.of(
            "SuperSuper.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "class SuperSuper {",
                    "  public void publicMethod() { }",
                    "}"),
            "Super.java",
            Joiner.on('\n')
                .join("package com.example.buck;", "public class Super extends SuperSuper {", "}"),
            "Subclass.java",
            Joiner.on('\n')
                .join("package com.example.buck;", "public class Subclass extends Super {", "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    assertNoNeededBridges(subclass);
  }

  @Test
  public void testNoAccessibilityBridgesToOverriddenMethods() throws IOException {
    compile(
        ImmutableMap.of(
            "Super.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "class Super {",
                    "  public void publicMethod() { }",
                    "}"),
            "Subclass.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public class Subclass extends Super {",
                    "  @Override",
                    "  public void publicMethod() { }",
                    "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    assertNoNeededBridges(subclass);
  }

  @Test
  public void testNoAccessibilityBridgesToHiddenMethods() throws IOException {
    compile(
        ImmutableMap.of(
            "Super.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "class Super {",
                    "  public void publicMethod() { }",
                    "}"),
            "Subclass.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public class Subclass extends Super {",
                    "  public static void publicMethod() { }",
                    "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    assertNoNeededBridges(subclass);
  }

  @Test
  public void testFindsAccessibilityBridgesToMethodsHidingStatics() throws IOException {
    compile(
        ImmutableMap.of(
            "SuperSuper.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "class SuperSuper {",
                    "  public static void publicMethod() { }",
                    "}"),
            "Super.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "class Super extends SuperSuper {",
                    "  public void publicMethod() { }",
                    "}"),
            "Subclass.java",
            Joiner.on('\n')
                .join("package com.example.buck;", "public class Subclass extends Super {", "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    TypeElement superclass = elements.getTypeElement("com.example.buck.Super");
    assertNeededBridges(
        subclass,
        new BridgeMethod(
            getMethod(superclass, "publicMethod"), getMethod(superclass, "publicMethod")));
  }

  @Test
  public void testNoAccessibilityBridgesToMethodsHiddenInSuper() throws IOException {
    compile(
        ImmutableMap.of(
            "SuperSuper.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "class SuperSuper {",
                    "  public void publicMethod() { }",
                    "}"),
            "Super.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "class Super extends SuperSuper {",
                    "  public static void publicMethod() { }",
                    "}"),
            "Subclass.java",
            Joiner.on('\n')
                .join("package com.example.buck;", "public class Subclass extends Super {", "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    assertNoNeededBridges(subclass);
  }

  @Test
  public void testNoAccessibilityBridgesToInterfaceMethods() throws IOException {
    compile(
        ImmutableMap.of(
            "Super.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "interface Super {",
                    "  void publicMethod();",
                    "}"),
            "Subclass.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;", "public class Subclass implements Super {", "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    assertNoNeededBridges(subclass);
  }

  @Test
  public void testNoAccessibilityBridgesToDefaultMethods() throws IOException {
    compile(
        ImmutableMap.of(
            "Super.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "interface Super {",
                    "  default void publicMethod() { }",
                    "}"),
            "Subclass.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;", "public class Subclass implements Super {", "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    assertNoNeededBridges(subclass);
  }

  @Test
  public void testNoAccessibilityBridgesToStaticMethods() throws IOException {
    testNoNeededBridgesForMethodWithModifiers("public", "static");
  }

  @Test
  public void testNoAccessibilityBridgesToPackageMethods() throws IOException {
    testNoNeededBridgesForMethodWithModifiers();
  }

  @Test
  public void testNoAccessibilityBridgesToProtectedMethods() throws IOException {
    testNoNeededBridgesForMethodWithModifiers("protected");
  }

  @Test
  public void testNoAccessibilityBridgesToPrivateMethods() throws IOException {
    testNoNeededBridgesForMethodWithModifiers("private");
  }

  @Test
  public void testNoAccessibilityBridgesToAbstractMethods() throws IOException {
    testNoNeededBridgesForMethodWithModifiers("public", "abstract");
  }

  private void testNoNeededBridgesForMethodWithModifiers(String... modifiers) throws IOException {
    compile(
        ImmutableMap.of(
            "Super.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "class Super {",
                    String.format("  %s void publicMethod() { }", Joiner.on(' ').join(modifiers)),
                    "}"),
            "Subclass.java",
            Joiner.on('\n')
                .join("package com.example.buck;", "public class Subclass extends Super { }")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    assertNoNeededBridges(subclass);
  }

  @Test
  public void testFindsCovariantReturnBridge() throws IOException {
    compile(
        ImmutableMap.of(
            "Super.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public class Super {",
                    "  public CharSequence publicMethod() { return null; }",
                    "}"),
            "Subclass.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public class Subclass extends Super {",
                    "  @Override",
                    "  public String publicMethod() { return null; }",
                    "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    TypeElement superclass = elements.getTypeElement("com.example.buck.Super");
    assertNeededBridges(
        subclass,
        new BridgeMethod(
            getMethod(subclass, "publicMethod"), getMethod(superclass, "publicMethod")));
  }

  @Test
  public void testNoFindsGenericSpecializationBridgeWithoutImplementation() throws IOException {
    compile(
        ImmutableMap.of(
            "SuperSuper.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public abstract class SuperSuper<T extends CharSequence> {",
                    "  public abstract void publicMethod(T t);",
                    "}"),
            "Super.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public abstract class Super<T extends CharSequence> extends SuperSuper<T> {",
                    "  public abstract void publicMethod(T t);",
                    "}"),
            "Subclass.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public abstract class Subclass extends Super<String> {",
                    "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    assertNoNeededBridges(subclass);
  }

  @Test
  public void testNoFindsGenericSpecializationBridgeFromInterfacesWithoutImplementation()
      throws IOException {
    compile(
        ImmutableMap.of(
            "SuperSuper.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public interface SuperSuper<T extends CharSequence> {",
                    "  void publicMethod(T t);",
                    "}"),
            "Super.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public interface Super<T extends CharSequence> extends SuperSuper<T> {",
                    "  void publicMethod(T t);",
                    "}"),
            "Subclass.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public abstract class Subclass implements Super<String> {",
                    "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    assertNoNeededBridges(subclass);
  }

  @Test
  public void testNoFindsCovariantReturnBridgeInInterfacesWithoutImplementation()
      throws IOException {
    compile(
        ImmutableMap.of(
            "SuperSuper.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public interface SuperSuper {",
                    "  CharSequence publicMethod();",
                    "}"),
            "Super.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public interface Super extends SuperSuper {",
                    "  String publicMethod();",
                    "}"),
            "Subclass.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public abstract class Subclass implements Super {",
                    "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    assertNoNeededBridges(subclass);
  }

  @Test
  public void testFindsCovariantReturnBridgeInInterfacesWithImplementation() throws IOException {
    compile(
        ImmutableMap.of(
            "Super.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public interface Super {",
                    "  CharSequence publicMethod();",
                    "}"),
            "Subclass.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public abstract class Subclass implements Super {",
                    "  public abstract String publicMethod();",
                    "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    TypeElement superclass = elements.getTypeElement("com.example.buck.Super");
    assertNeededBridges(
        subclass,
        new BridgeMethod(
            getMethod(subclass, "publicMethod"), getMethod(superclass, "publicMethod")));
  }

  @Test
  public void testFindsBridgesBetweenSuperclassAndInterface() throws IOException {
    compile(
        ImmutableMap.of(
            "Super.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public class Super {",
                    "  public String publicMethod() { return null; }",
                    "}"),
            "Interface.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public interface Interface {",
                    "  CharSequence publicMethod();",
                    "}"),
            "Subclass.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public class Subclass extends Super implements Interface {",
                    "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    TypeElement superclass = elements.getTypeElement("com.example.buck.Super");
    TypeElement iface = elements.getTypeElement("com.example.buck.Interface");
    assertNeededBridges(
        subclass,
        new BridgeMethod(getMethod(superclass, "publicMethod"), getMethod(iface, "publicMethod")));
  }

  @Test
  public void testNoBridgesIfSuperHasAlreadyBridged() throws IOException {
    compile(
        ImmutableMap.of(
            "Super.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public class Super implements Interface {",
                    "  public String publicMethod() { return null; }",
                    "}"),
            "Interface.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public interface Interface {",
                    "  public CharSequence publicMethod();",
                    "}"),
            "Subclass.java",
            Joiner.on('\n')
                .join("package com.example.buck;", "public class Subclass extends Super {", "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    assertNoNeededBridges(subclass);
  }

  @Test
  public void testNoBridgesIfSuperHasAlreadyBridgedSeparately() throws IOException {
    compile(
        ImmutableMap.of(
            "Interface.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public interface Interface {",
                    "  public CharSequence publicMethod();",
                    "}"),
            "Super.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public class Super implements Interface2 {",
                    "  public String publicMethod() { return null; }",
                    "}"),
            "Interface2.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public interface Interface2 {",
                    "  public CharSequence publicMethod();",
                    "}"),
            "Subclass.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public class Subclass extends Super implements Interface {",
                    "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    assertNoNeededBridges(subclass);
  }

  @Test
  /**
   * Javac adds bridges to inner classes before outer classes, which can result in some extra
   * bridges getting created; make sure we match the behavior.
   */
  public void testAddsBridgesForSuperWhenItIsAnOuterClassOfSubclass() throws IOException {
    compile(
        ImmutableMap.of(
            "SuperSuper.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public class SuperSuper {",
                    "  public Object publicMethod() { return null; }",
                    "}"),
            "Super.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public class Super extends SuperSuper {",
                    "  @Override",
                    "  public CharSequence publicMethod() { return null; }",
                    "  public static class Subclass extends Super {",
                    "  }",
                    "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Super.Subclass");
    TypeElement superclass = elements.getTypeElement("com.example.buck.Super");
    TypeElement superSuperClass = elements.getTypeElement("com.example.buck.SuperSuper");
    assertNeededBridges(
        subclass,
        new BridgeMethod(
            getMethod(superclass, "publicMethod"), getMethod(superSuperClass, "publicMethod")));
  }

  @Test
  public void testFindsGenericSpecializationBridge() throws IOException {
    compile(
        ImmutableMap.of(
            "Subclass.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public class Subclass implements Comparable<String> {",
                    "  @Override",
                    "  public int compareTo(String other) { return 0; }",
                    "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    TypeElement comparable = elements.getTypeElement("java.lang.Comparable");
    assertNeededBridges(
        subclass,
        new BridgeMethod(getMethod(subclass, "compareTo"), getMethod(comparable, "compareTo")));
  }

  @Test
  public void testNoBridgeForNonSpecializedGeneric() throws IOException {
    compile(
        ImmutableMap.of(
            "Subclass.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public abstract class Subclass extends java.util.AbstractMap<String, Integer> {",
                    "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    assertNoNeededBridges(subclass);
  }

  @Test
  public void testFindsBridgesInInterfaces() throws IOException {
    compile(
        ImmutableMap.of(
            "Subclass.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "import java.util.concurrent.Callable;",
                    "public interface Subclass extends Callable<CharSequence> {",
                    "  @Override",
                    "  String call();",
                    "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    TypeElement callable = elements.getTypeElement("java.util.concurrent.Callable");
    assertNeededBridges(
        subclass, new BridgeMethod(getMethod(subclass, "call"), getMethod(callable, "call")));
  }

  @Test
  public void testFindsMultipleGenericSpecializationBridges() throws IOException {
    compile(
        ImmutableMap.of(
            "Super.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public class Super<T extends CharSequence> implements Comparable<T> {",
                    "  @Override",
                    "  public int compareTo(T other) { return 0; }",
                    "}"),
            "Subclass.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public class Subclass extends Super<String> {",
                    "  @Override",
                    "  public int compareTo(String other) { return 0; }",
                    "}")));

    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    TypeElement superclass = elements.getTypeElement("com.example.buck.Super");
    TypeElement comparable = elements.getTypeElement("java.lang.Comparable");
    assertNeededBridges(
        subclass,
        new BridgeMethod(getMethod(subclass, "compareTo"), getMethod(superclass, "compareTo")),
        new BridgeMethod(getMethod(subclass, "compareTo"), getMethod(comparable, "compareTo")));
  }

  /**
   * A hypothetical bridge is one that the compiler adds to its internal data structures when the
   * erasure would be different in the subclass, but there is no override and thus no runtime
   * confusion. It adds this bridge to detect erasure clashes, but it does not actually get emitted
   * to the class file.
   */
  @Test
  public void testNoHypotheticalBridges() throws IOException {
    compile(
        ImmutableMap.of(
            "Super.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public class Super<T> {",
                    "  public T method() { return null; }",
                    "}"),
            "Subclass.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public class Subclass extends Super<String> { }")));
    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    assertNoNeededBridges(subclass);
  }

  @Test
  /**
   * This might be a bug in javac, but if it creates a hypothetical bridge (see {@link
   * #testNoHypotheticalBridges()}) it will not go down the accessibility bridge path.
   */
  public void testNoHypotheticalBridgesEvenIfAccessibilityBridgeWouldOtherwiseApply()
      throws IOException {
    compile(
        ImmutableMap.of(
            "Super.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "class Super<T> {",
                    "  public T method() { return null; }",
                    "}"),
            "Subclass.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public class Subclass extends Super<String> { }")));
    TypeElement subclass = elements.getTypeElement("com.example.buck.Subclass");
    assertNoNeededBridges(subclass);
  }

  private void assertNoNeededBridges(TypeElement subclass) {
    assertThat(elements.getAllBridgeMethods(subclass), Matchers.empty());
  }

  private void assertNeededBridges(TypeElement subclass, BridgeMethod... expected) {
    assertThat(elements.getAllBridgeMethods(subclass), Matchers.contains(expected));
  }

  private ExecutableElement getMethod(TypeElement owner, CharSequence name) {
    List<ExecutableElement> methods = elements.getDeclaredMethods(owner, name);

    assertThat(methods.size(), Matchers.equalTo(1));

    return methods.get(0);
  }
}
