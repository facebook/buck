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

package com.facebook.buck.jvm.java;

import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;
import static org.junit.Assert.assertEquals;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSortedSet;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class JavaFileParserTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  private static final String JAVA_CODE_WITH_MANY_CLASSES =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "public class Example {",
              "  public static int variablesNotCaptured, maybeLater;",
              "",
              "  private Example() {}",
              "",
              "  public static void functionsNotCapturedEither() {",
              "  }",
              "",
              "  public enum InnerEnum {",
              "    foo;",
              "",
              "    public class InnerClass {",
              "    }",
              "  }",
              "",
              "  interface InnerInterface {",
              "  }",
              "}",
              "",
              "class AnotherOuterClass {",
              "}");

  @Test
  public void testJavaFileParsing() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);

    ImmutableSortedSet<String> symbols =
        parser.getExportedSymbolsFromString(JAVA_CODE_WITH_MANY_CLASSES);

    assertEquals(
        "getExportedSymbolsFromString should extract both top-level and inner symbols as provided",
        ImmutableSortedSet.of(
            "com.example.AnotherOuterClass",
            "com.example.Example",
            "com.example.Example.InnerEnum",
            "com.example.Example.InnerEnum.InnerClass",
            "com.example.Example.InnerInterface"),
        symbols);
  }

  private static final String JAVA_CODE_WITH_LOCAL_CLASS_IN_ANONYMOUS_CLASS =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "public class NonlocalClass {",
              "  @Override",
              "  Iterator<Entry<K, V>> entryIterator() {",
              "    return new Itr<Entry<K, V>>() {",
              "      @Override",
              "      Entry<K, V> output(BiEntry<K, V> entry) {",
              "        return new MapEntry(entry);",
              "      }",
              "",
              "      class MapEntry  {}",
              "    };",
              "  }",
              "}");

  @Test
  public void testJavaFileParsingWithLocalClassInAnonymousClass() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);

    ImmutableSortedSet<String> symbols =
        parser.getExportedSymbolsFromString(JAVA_CODE_WITH_LOCAL_CLASS_IN_ANONYMOUS_CLASS);

    assertEquals(
        "getExportedSymbolsFromString should not consider non-local classes to be provided",
        ImmutableSortedSet.of("com.example.NonlocalClass"),
        symbols);
  }

  private static final String JAVA_CODE_WITH_LOCAL_CLASS =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "public class NonlocalClass {",
              "  public static void exampleMethod() {",
              "    class LocalClass {",
              "    }",
              "  }",
              "}");

  @Test
  public void testJavaFileParsingWithLocalClass() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);

    ImmutableSortedSet<String> symbols =
        parser.getExportedSymbolsFromString(JAVA_CODE_WITH_LOCAL_CLASS);

    assertEquals(
        "getExportedSymbolsFromString should not consider non-local classes to be provided",
        ImmutableSortedSet.of("com.example.NonlocalClass"),
        symbols);
  }

  private static final String JAVA_CODE_WITH_NO_PACKAGE = "public class NoPackageExample { }";

  @Test
  public void testJavaFileParsingWithNoPackage() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);

    ImmutableSortedSet<String> symbols =
        parser.getExportedSymbolsFromString(JAVA_CODE_WITH_NO_PACKAGE);

    assertEquals(
        "getExportedSymbolsFromString should be able to extract package-less classes as provided",
        ImmutableSortedSet.of("NoPackageExample"),
        symbols);
  }

  private static final String JAVA_CODE_WITH_ANNOTATION_TYPE =
      "public @interface ExampleAnnotationType { }";

  @Test
  public void testJavaFileParsingWithAnnotationType() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);

    ImmutableSortedSet<String> symbols =
        parser.getExportedSymbolsFromString(JAVA_CODE_WITH_ANNOTATION_TYPE);

    assertEquals(
        "getExportedSymbolsFromString should be able to extract symbols with annotations",
        ImmutableSortedSet.of("ExampleAnnotationType"),
        symbols);
  }

  private static final String JAVA_CODE_WITH_IMPORTS =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "import java.util.Map;",
              "",
              "public class AnExample {",
              "",
              "  public void doStuff(Map map) {",
              "",
              "  }",
              "}");

  @Test
  public void testExtractingRequiredSymbols() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);

    JavaFileParser.JavaFileFeatures features =
        parser.extractFeaturesFromJavaCode(JAVA_CODE_WITH_IMPORTS);

    assertEquals(
        "extractFeaturesFromJavaCode should be able to find a top-level class",
        ImmutableSortedSet.of("com.example.AnExample"),
        features.providedSymbols);
    assertEquals(ImmutableSortedSet.of(), features.requiredSymbols);
    assertEquals(
        "extractFeaturesFromJavaCode should be able to find an ordinary import",
        ImmutableSortedSet.of("java.util.Map"),
        features.exportedSymbols);
  }

  private static final String JAVA_CODE_WITH_IMPORTS_THAT_DO_NOT_FOLLOW_THE_NAMING_CONVENTIONS =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "import com.facebook.buck.nsOuter.nsInner;",
              "",
              "import org.mozilla.intl.chardet.nsDetector;",
              "import org.mozilla.intl.chardet.nsICharsetDetectionObserver;",
              "import org.mozilla.intl.chardet.nsPSMDetector;",
              "",
              "public class AnExample {}");

  @Test
  public void testExtractingRequiredSymbolsWithImportsThatDoNotFollowTheNamingConventions() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);
    JavaFileParser.JavaFileFeatures features =
        parser.extractFeaturesFromJavaCode(
            JAVA_CODE_WITH_IMPORTS_THAT_DO_NOT_FOLLOW_THE_NAMING_CONVENTIONS);

    assertEquals(
        ImmutableSortedSet.of(
            "com.facebook.buck.nsOuter",
            "org.mozilla.intl.chardet.nsDetector",
            "org.mozilla.intl.chardet.nsICharsetDetectionObserver",
            "org.mozilla.intl.chardet.nsPSMDetector"),
        features.requiredSymbols);
    assertEquals(ImmutableSortedSet.of(), features.exportedSymbols);
  }

  private static final String JAVA_CODE_WITH_IMPORTS_THAT_HAVE_NO_CAPITAL_LETTERS =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "import com.facebook.buck.badactor;",
              "",
              "public class AnExample {}");

  /**
   * Verifies that an import that completely violates the expectations around naming conventions
   * will still be added. Although we can police this in our own code, we have no control over what
   * third-party libraries do.
   */
  @Test
  public void testExtractingRequiredSymbolsWithImportsThatHaveNoCapitalLetters() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);
    JavaFileParser.JavaFileFeatures features =
        parser.extractFeaturesFromJavaCode(JAVA_CODE_WITH_IMPORTS_THAT_HAVE_NO_CAPITAL_LETTERS);

    assertEquals(ImmutableSortedSet.of("com.facebook.buck.badactor"), features.requiredSymbols);
    assertEquals(ImmutableSortedSet.of("com.example.AnExample"), features.providedSymbols);
  }

  private static final String JAVA_CODE_WITH_FULLY_QUALIFIED_REFERENCES =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "import java.util.Map;",
              "",
              "public class AnExample {",
              "",
              "  public int getMeaningOfLife() {",
              "    String meaningOfLife = ClassInThisPackage.MEANING_OF_LIFE;",
              "    return java.lang.Integer.valueOf(meaningOfLife, 10);",
              "  }",
              "}");

  @Test
  public void testExtractingRequiredSymbolsWithFullyQualifiedReference() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);
    JavaFileParser.JavaFileFeatures features =
        parser.extractFeaturesFromJavaCode(JAVA_CODE_WITH_FULLY_QUALIFIED_REFERENCES);

    assertEquals(
        "extractFeaturesFromJavaCode should be able to find a top-level class",
        ImmutableSortedSet.of("com.example.AnExample"),
        features.providedSymbols);
    assertEquals(
        "extractFeaturesFromJavaCode should be able to make appropriate inferences about: "
            + "(1) unqualified references to types in the same package "
            + "(2) fully-qualified references to types",
        ImmutableSortedSet.of(
            "java.util.Map", "com.example.ClassInThisPackage", "java.lang.Integer"),
        features.requiredSymbols);
    assertEquals(ImmutableSortedSet.of(), features.exportedSymbols);
  }

  private static final String JAVA_CODE_WITH_STATIC_IMPORT =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;",
              "",
              "public class AnExample {",
              "  public void executor() { listeningDecorator(null); }",
              "}");

  @Test
  public void testExtractingRequiredSymbolsWithStaticImport() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);
    JavaFileParser.JavaFileFeatures features =
        parser.extractFeaturesFromJavaCode(JAVA_CODE_WITH_STATIC_IMPORT);

    assertEquals(
        ImmutableSortedSet.of("com.google.common.util.concurrent.MoreExecutors"),
        features.requiredSymbols);
    assertEquals(ImmutableSortedSet.of(), features.exportedSymbols);
  }

  /**
   * Note that using a wildcard import is frequently a "poison pill" when it comes to extracting
   * required symbols via {@link JavaFileParser}; however, there is some hardcoded support for
   * common wildcards, such as {@code import java.util.*}.
   */
  private static final String JAVA_CODE_WITH_SUPPORTED_WILDCARD_IMPORT =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "import java.util.*;",
              "",
              "public class AnExample {",
              "  public Map newInstance() { return new HashMap(); }",
              "}");

  @Test
  public void testExtractingRequiredSymbolsWithSupportedWildcardImport() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);
    JavaFileParser.JavaFileFeatures features =
        parser.extractFeaturesFromJavaCode(JAVA_CODE_WITH_SUPPORTED_WILDCARD_IMPORT);

    assertEquals(ImmutableSortedSet.of("com.example.AnExample"), features.providedSymbols);
    assertEquals(ImmutableSortedSet.of("java.util.HashMap"), features.requiredSymbols);
    assertEquals(ImmutableSortedSet.of("java.util.Map"), features.exportedSymbols);
  }

  private static final String JAVA_CODE_WITH_UNSUPPORTED_WILDCARD_IMPORT =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "import com.example.things.Thinger;",
              "import com.mystery.*;",
              "",
              "import java.util.*;",
              "",
              "public class AnExample extends FooBar {",
              "  public Map newInstance() { return new HashMap(); }",
              "  public SomeExample newThinger() { return Thinger.createSomeExample(); }",
              "}");

  @Test
  public void testExtractingRequiredSymbolsWithUnsupportedWildcardImport() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);
    JavaFileParser.JavaFileFeatures features =
        parser.extractFeaturesFromJavaCode(JAVA_CODE_WITH_UNSUPPORTED_WILDCARD_IMPORT);

    assertEquals(ImmutableSortedSet.of("com.example.AnExample"), features.providedSymbols);
    assertEquals(
        "Should be restricted to explicit imports because the wildcard import makes it "
            + "impossible for JavaFileParser to know things such as whether it is "
            + "com.example.FooBar or com.mystery.FooBar "
            + "(this is also true for Map and HashMap, as things are implemented today).",
        ImmutableSortedSet.of("com.example.things.Thinger"),
        features.requiredSymbols);
    assertEquals(
        "Currently contains some items, but ideally would be empty.",
        ImmutableSortedSet.of("java.util.Map", "com.example.FooBar", "com.example.SomeExample"),
        features.exportedSymbols);
  }

  private static final String JAVA_CODE_THROWS_FULLY_QUALIFIED_EXCEPTION =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "public class AnExample {",
              "  public void read() throws java.io.IOException {}",
              "}");

  @Test
  public void testExtractingRequiredSymbolsWithFullyQualifiedThrows() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);
    JavaFileParser.JavaFileFeatures features =
        parser.extractFeaturesFromJavaCode(JAVA_CODE_THROWS_FULLY_QUALIFIED_EXCEPTION);

    assertEquals(
        "extractFeaturesFromJavaCode should be able to find a top-level class",
        ImmutableSortedSet.of("com.example.AnExample"),
        features.providedSymbols);
    assertEquals(ImmutableSortedSet.of(), features.requiredSymbols);
    assertEquals(ImmutableSortedSet.of("java.io.IOException"), features.exportedSymbols);
  }

  private static final String JAVA_CODE_INSTANTIATES_CLASS_IN_PACKAGE =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "public class AnExample {",
              "  public Widget create() { return new Widget(); }",
              "}");

  @Test
  public void testExtractingRequiredSymbolsWithNewTypeInPackage() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);
    JavaFileParser.JavaFileFeatures features =
        parser.extractFeaturesFromJavaCode(JAVA_CODE_INSTANTIATES_CLASS_IN_PACKAGE);

    assertEquals(
        "extractFeaturesFromJavaCode should be able to find a top-level class",
        ImmutableSortedSet.of("com.example.AnExample"),
        features.providedSymbols);
    assertEquals(ImmutableSortedSet.of(), features.requiredSymbols);
    assertEquals(ImmutableSortedSet.of("com.example.Widget"), features.exportedSymbols);
  }

  private static final String JAVA_CODE_CREATES_IN_PACKAGE_TYPE_WITHIN_PACKAGE_TYPE =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "public class AnExample {",
              "  public Widget create() { return new Widget(new Woojet()); }",
              "}");

  @Test
  public void testExtractingRequiredSymbolsRecursesIntoNewCall() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);
    JavaFileParser.JavaFileFeatures features =
        parser.extractFeaturesFromJavaCode(JAVA_CODE_CREATES_IN_PACKAGE_TYPE_WITHIN_PACKAGE_TYPE);

    assertEquals(ImmutableSortedSet.of("com.example.Woojet"), features.requiredSymbols);
    assertEquals(ImmutableSortedSet.of("com.example.Widget"), features.exportedSymbols);
  }

  private static final String JAVA_CODE_DOES_INSTANCEOF_CHECK_FOR_TYPE_WITHIN_PACKAGE =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "public class AnExample {",
              "  public boolean isWoojet(Object widget) { return widget instanceof Woojet; }",
              "}");

  @Test
  public void testExtractingRequiredSymbolsWithInstanceofCheckInPackage() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);
    JavaFileParser.JavaFileFeatures features =
        parser.extractFeaturesFromJavaCode(JAVA_CODE_DOES_INSTANCEOF_CHECK_FOR_TYPE_WITHIN_PACKAGE);

    assertEquals(ImmutableSortedSet.of("com.example.Woojet"), features.requiredSymbols);
    assertEquals(ImmutableSortedSet.of(), features.exportedSymbols);
  }

  private static final String JAVA_CODE_DOES_CAST_FOR_TYPE_WITHIN_PACKAGE =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "public class AnExample {",
              "  public void castToWidget(Object widget) { Object obj = (Widget) widget; }",
              "}");

  @Test
  public void testExtractingRequiredSymbolsWithCastToTypeInPackage() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);
    JavaFileParser.JavaFileFeatures features =
        parser.extractFeaturesFromJavaCode(JAVA_CODE_DOES_CAST_FOR_TYPE_WITHIN_PACKAGE);

    assertEquals(ImmutableSortedSet.of("com.example.Widget"), features.requiredSymbols);
    assertEquals(ImmutableSortedSet.of(), features.exportedSymbols);
  }

  private static final String JAVA_CODE_DOES_CAST_FOR_TYPE_WITHIN_PACKAGE_IN_METHOD_INVOCATION =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "public class AnExample {",
              "  public Object createWidget() { return create((Widget) null); }",
              "}");

  @Test
  public void testExtractingRequiredSymbolsWithCastToTypeInPackageWithinMethodInvocation() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);
    JavaFileParser.JavaFileFeatures features =
        parser.extractFeaturesFromJavaCode(
            JAVA_CODE_DOES_CAST_FOR_TYPE_WITHIN_PACKAGE_IN_METHOD_INVOCATION);

    assertEquals(ImmutableSortedSet.of("com.example.Widget"), features.requiredSymbols);
    assertEquals(ImmutableSortedSet.of(), features.exportedSymbols);
  }

  private static final String JAVA_CODE_SPECIFIES_PARAM_FOR_TYPE_WITHIN_PACKAGE =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "public class AnExample {",
              "  public void printWidget(Widget widget) { System.out.println(widget); }",
              "}");

  @Test
  public void testExtractingRequiredSymbolsWithParamInPackage() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);
    JavaFileParser.JavaFileFeatures features =
        parser.extractFeaturesFromJavaCode(JAVA_CODE_SPECIFIES_PARAM_FOR_TYPE_WITHIN_PACKAGE);

    assertEquals(ImmutableSortedSet.of(), features.requiredSymbols);
    assertEquals(ImmutableSortedSet.of("com.example.Widget"), features.exportedSymbols);
  }

  private static final String JAVA_CODE_SPECIFIES_STATIC_METHOD_IN_PACKAGE =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "public class AnExample {",
              "  public Object createWidget() { return Widget.newInstance(); }",
              "}");

  @Test
  public void testExtractingRequiredSymbolsWithStaticMethodAccess() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);
    JavaFileParser.JavaFileFeatures features =
        parser.extractFeaturesFromJavaCode(JAVA_CODE_SPECIFIES_STATIC_METHOD_IN_PACKAGE);

    assertEquals(ImmutableSortedSet.of("com.example.Widget"), features.requiredSymbols);
    assertEquals(ImmutableSortedSet.of(), features.exportedSymbols);
  }

  private static final String JAVA_CODE_SPECIFIES_TYPE_IN_PACKAGE =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "public class AnExample {",
              "  public void createWidget() {",
              "    Widget widget = WidgetFactory.newInstance();",
              "  }",
              "}");

  @Test
  public void testExtractingRequiredSymbolsWithTypeOnlyReferencedAsLocalVariable() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);
    JavaFileParser.JavaFileFeatures features =
        parser.extractFeaturesFromJavaCode(JAVA_CODE_SPECIFIES_TYPE_IN_PACKAGE);

    assertEquals(
        ImmutableSortedSet.of("com.example.Widget", "com.example.WidgetFactory"),
        features.requiredSymbols);
    assertEquals(ImmutableSortedSet.of(), features.exportedSymbols);
  }

  private static final String PROPERTY_LOOKUP_EXPRESSION =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "import java.util.*;",
              "",
              "public class AnExample {",
              "  public static void main(String[] args) {",
              "    int numArgs = args.length;",
              "    List<String> asArray = new ArrayList(numArgs);",
              "  }",
              "}");

  @Test
  public void testExtractingRequiredSymbolsWithPropertyLookupExpression() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);
    JavaFileParser.JavaFileFeatures features =
        parser.extractFeaturesFromJavaCode(PROPERTY_LOOKUP_EXPRESSION);

    assertEquals(
        "args.length should not appear in features.requiredSymbols.",
        ImmutableSortedSet.of("java.util.ArrayList", "java.util.List"),
        features.requiredSymbols);
    assertEquals(ImmutableSortedSet.of(), features.exportedSymbols);
  }

  private static final String JAVA_FULL_FEATURED_EXAMPLE =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "public class AnExample extends AnotherExample implements IExample {",
              "  private MyField myField;",
              "",
              "  @AnnotationWithArgs(str = MoarConstants.COMPILE_TIME_CONSTANT)",
              "  @ChecksMeaning(42)",
              "  @Edible",
              "  public static MyExample createFrom(Foo foo, Bar bar) throws MyException {",
              "    FooAndBar foobar = new FooAndBarSubclass(foo, bar);",
              "    foobar.init(Thinger.class, com.otherexample.Thinger.class);",
              "    int meaningOfLife = foobar.someMethod().otherMethod().okLastMethod();",
              "    if (meaningOfLife == Constants.MEANING_OF_LIFE) {",
              "      return MyExampleFactory.newInstance(meaningOfLife);",
              "    } else {",
              "      Object[] unused = new MyArray[42];",
              "      Object[] unused2 = new MyArray2[] { null };",
              "      try {",
              "        unused2.toString();",
              "      } catch (BizarreCheckedException e) {}",
              "      return null;",
              "    }",
              "  }",
              "}");

  @Test
  public void testExtractingRequiredSymbolsWithNonTrivialJavaLogic() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);
    JavaFileParser.JavaFileFeatures features =
        parser.extractFeaturesFromJavaCode(JAVA_FULL_FEATURED_EXAMPLE);

    assertEquals(
        ImmutableSortedSet.of(
            "com.example.BizarreCheckedException",
            "com.example.Constants",
            "com.example.FooAndBar",
            "com.example.FooAndBarSubclass",
            "com.example.MoarConstants",
            "com.example.MyArray",
            "com.example.MyArray2",
            "com.example.MyExampleFactory",
            "com.example.MyField",
            "com.example.Thinger",
            "com.otherexample.Thinger"),
        features.requiredSymbols);
    assertEquals(
        ImmutableSortedSet.of(
            "com.example.AnnotationWithArgs",
            "com.example.AnotherExample",
            "com.example.Bar",
            "com.example.ChecksMeaning",
            "com.example.Edible",
            "com.example.Foo",
            "com.example.IExample",
            "com.example.MyExample",
            "com.example.MyException"),
        features.exportedSymbols);
  }

  private static final String EXPORTED_TYPES_EXAMPLE =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "public class AnExample extends SuperExample implements IExample1, IExample2 {",
              "  public PublicField publicField;",
              "  protected ProtectedField protectedField;",
              "  PackagePrivateField packagePrivateField;",
              "  private PrivateField privateField;",
              "",
              "  public static PublicReturnType createFrom(PublicParam p) throws PublicException {",
              "    return null;",
              "  }",
              "",
              "  protected static ProtectedReturnType createFrom(ProtectedParam p) ",
              "      throws ProtectedException {",
              "    return null;",
              "  }",
              "",
              "  static PackagePrivateReturnType createFrom(PackagePrivateParam p) ",
              "      throws PackagePrivateException {",
              "    return null;",
              "  }",
              "",
              "  private static PrivateReturnType createFrom(PrivateParam p) throws PrivateException {",
              "    return null;",
              "  }",
              "}");

  @Test
  public void testExtractingExportedTypes() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);
    JavaFileParser.JavaFileFeatures features =
        parser.extractFeaturesFromJavaCode(EXPORTED_TYPES_EXAMPLE);
    assertEquals(
        ImmutableSortedSet.of(
            "com.example.IExample1",
            "com.example.IExample2",
            "com.example.PublicException",
            "com.example.PublicField",
            "com.example.PublicParam",
            "com.example.PublicReturnType",
            "com.example.ProtectedException",
            "com.example.ProtectedField",
            "com.example.ProtectedParam",
            "com.example.ProtectedReturnType",
            "com.example.PackagePrivateException",
            "com.example.PackagePrivateField",
            "com.example.PackagePrivateParam",
            "com.example.PackagePrivateReturnType",
            "com.example.SuperExample"),
        features.exportedSymbols);
  }

  private static final String EXPORTED_TYPES_INTERFACE_EXAMPLE =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "import com.example.interfaces.IExample;",
              "",
              "public interface Example extends IExample {",
              "}");

  @Test
  public void testExtractingExportedTypesFromInterfaceThatExtendsInterfaceFromAnotherPackage() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);
    JavaFileParser.JavaFileFeatures features =
        parser.extractFeaturesFromJavaCode(EXPORTED_TYPES_INTERFACE_EXAMPLE);
    assertEquals(ImmutableSortedSet.of(), features.requiredSymbols);
    assertEquals(ImmutableSortedSet.of("com.example.Example"), features.providedSymbols);
    assertEquals(
        ImmutableSortedSet.of("com.example.interfaces.IExample"), features.exportedSymbols);
  }

  private static final String EXPORTED_TYPES_SUPERCLASS_WITH_GENERIC =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "import com.example.interfaces.IExample;",
              "",
              "public class Example<T> extends IExample<T> {",
              "}");

  @Test
  public void testExtractingExportedTypesFromSuperclassWithAGeneric() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);
    JavaFileParser.JavaFileFeatures features =
        parser.extractFeaturesFromJavaCode(EXPORTED_TYPES_SUPERCLASS_WITH_GENERIC);
    assertEquals(ImmutableSortedSet.of(), features.requiredSymbols);
    assertEquals(ImmutableSortedSet.of("com.example.Example"), features.providedSymbols);
    assertEquals(
        ImmutableSortedSet.of("com.example.interfaces.IExample"), features.exportedSymbols);
  }

  private static final String EXPORTED_TYPES_WITH_CLASS_THAT_LOOKS_LIKE_A_GENERIC =
      Joiner.on('\n')
          .join(
              "package com.example;",
              "",
              "import com.google.common.base.Function;",
              "import org.stringtemplate.v4.ST;",
              "",
              "public class Example {",
              "  public StringTemplateStep(Function<ST, ST> configure) {}",
              "}");

  /** This is a case that we ran into in Buck's own source code. */
  @Test
  public void testExtractingExportedTypesWithClassThatLooksLikeAGeneric() {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);
    JavaFileParser.JavaFileFeatures features =
        parser.extractFeaturesFromJavaCode(EXPORTED_TYPES_WITH_CLASS_THAT_LOOKS_LIKE_A_GENERIC);
    assertEquals(ImmutableSortedSet.of(), features.requiredSymbols);
    assertEquals(ImmutableSortedSet.of("com.example.Example"), features.providedSymbols);
    assertEquals(
        ImmutableSortedSet.of("com.google.common.base.Function", "org.stringtemplate.v4.ST"),
        features.exportedSymbols);
  }
}
