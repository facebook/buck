/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.java.abi;

import static com.google.common.base.Charsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.SortedSet;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public class AbiWriterTest {

  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void abiKeyForEmptySourcesIsStable() {
    assertEquals(
        "The ABI key used when a java_library() has no srcs should be constant across platforms.",
        AbiWriterProtocol.EMPTY_ABI_KEY,
        AbiWriter.computeAbiKey(ImmutableSortedSet.<String>of()));
  }

  @Test
  public void willCaptureClassName() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public class A {}"));

    assertTrue(summary, summary.contains("public class com.facebook.buck.example.A"));
  }

  @Test
  public void willCaptureTypeParametersOnClass() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public class A<T> {}"));

    assertTrue(summary, summary.contains("com.facebook.buck.example.A<T>"));
  }

  @Test
  public void classesDefaultToExtendingJavaLangObject() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public class A {}"));

    assertTrue(summary, summary.contains("extends java.lang.Object"));
  }

  @Test
  public void classesCanImplementManyInterfaces() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "import java.io.Serializable;",
        "public class A implements Cloneable, Serializable {}"));

    assertTrue("Ordering of interfaces is alphabetical based on fully qualified name" + summary,
        summary.contains("implements java.io.Serializable, java.lang.Cloneable"));
  }

  @Test
  public void classesCanImplementInterfacesThatHaveAGenericType() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public class A implements Comparable<A> {",
        "  public int compareTo(A o) {",
        "    return 1;",
        "  }",
        "}"));

    assertTrue(summary,
        summary.contains("implements java.lang.Comparable<com.facebook.buck.example.A>"));
  }

  @Test
  public void runtimeAnnotationsArePreservedAtTheClassLevel() throws IOException{
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "@Deprecated",
        "public class A {}"));

    assertTrue(summary, summary.contains("@java.lang.Deprecated"));
  }

  @Test
  public void classesCanHaveMethods() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public class A {",
        "  public int doSomething(String foo) {",
        "    return 0;",
        "  }",
        "}"));

    assertTrue(summary, summary.contains("public int doSomething(java.lang.String)"));

    // Make sure we've not output the method body.
    assertFalse(summary, summary.contains("return"));
  }

  @Test
  public void genericTypesOnClassMethodsAreCorrectlyReported() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public class A<Y> {",
        "  public <X> X doSomething(Y foo, A bar, String baz) {",
        "    return null;",
        "  }",
        "}"));

    assertTrue(summary, summary.contains(
        "<X> X doSomething(Y, com.facebook.buck.example.A, java.lang.String)"));
  }

  @Test
  public void retainedAnnotationsOnClassMethodsAreKept() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public class A {",
        "  @Deprecated public void foo() {}",
        "}"));

    assertTrue(summary, summary.contains("@java.lang.Deprecated"));
  }

  @Test
  public void interfacesDoNotExtendAnything() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "interface A {}"));

    assertTrue(summary, summary.contains("interface com.facebook.buck.example.A"));
  }

  @Test
  public void interfacesCanExtendOtherInterfaces() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "interface A extends java.io.Serializable {}"));

    assertTrue(summary, summary.contains("example.A extends java.io.Serializable"));
  }

  @Test
  public void interfacesRetainClassLevelAnnotations() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "@Deprecated public interface A {}"));

    assertTrue(summary, summary.contains("@java.lang.Deprecated"));
  }

  @Test
  public void interfacesCanBeTyped() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public interface A<T> {}"));

    assertEquals("public abstract interface com.facebook.buck.example.A<T>\n", summary);
  }

  @Test
  public void interfaceMethodsAreReported() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public interface A {",
        "  public int doSomething(String foo);",
        "  void sitQuietly();",
        "}"));

    assertTrue(summary, summary.contains("public abstract int doSomething(java.lang.String)"));
    assertTrue(summary, summary.contains("public abstract void sitQuietly()"));
  }

  @Test
  public void interfaceMethodsWithTypeSignaturesAreCorrectlyKept() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public interface A<Y> {",
        "  public <X> X doSomething(Y foo, A bar, String baz);",
        "}"));

    assertTrue(summary, summary.contains(
        "<X> X doSomething(Y, com.facebook.buck.example.A, java.lang.String)"));
  }

  @Test
  public void shouldNotIncludePrivateMethods() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public class A {",
        "  private void doSomething() {}",
        "}"));

    assertFalse(summary, summary.contains("doSomething"));
  }

  // Fields
  @Test
  public void visibleFieldsAreIncluded() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
      "package com.facebook.buck.example;",
      "public class A {",
      "  public int number = 42;",
      "  private String magic = \"Now you see me\";",
      "}"));

    assertTrue(summary, summary.contains("public int number"));
    assertFalse(summary, summary.contains("42"));
    assertFalse(summary, summary.contains("magic"));
  }

  @Test
  public void runTimeAnnotationsOnFieldsAreRetained() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public class A {",
        "  @Deprecated",
        "  public int number;",
        "}"));

    assertTrue(summary, summary.contains("@java.lang.Deprecated"));
  }

  @Test
  public void genericTypesOnFieldsAreKept() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "import java.util.Set;",
        "public class A {",
        "  public Set<String> strings;",
        "}"));

    assertTrue(summary, summary.contains("Set<java.lang.String> strings"));
  }

  @Test
  public void shouldMaintainWildCardGenerics() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "import java.util.Set;",
        "public class A {",
        "  public Set<?> question;",
        "}"));

    assertTrue(summary, summary.contains("Set<?> question"));
  }

  @Test
  public void shouldComplexGenerics() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "import java.util.Collection;",
        "import java.util.Set;",
        "public class A<T> {",
        "  public Set<? extends Collection> extension;",
        "  public Set<? super T> supered;",
        "}"));

    assertTrue(summary, summary.contains("Set<? extends java.util.Collection> extension"));
    assertTrue(summary, summary.contains("Set<? super T> supered"));
  }

  @Test
  public void nestedGenericsAreOkay() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "import java.util.Collection;",
        "import java.util.Set;",
        "public class A<T> {",
        "  public Set<Collection<String>> nested;",
        "}"));

    assertTrue(summary,
        summary.contains("java.util.Set<java.util.Collection<java.lang.String>> nested"));
  }

  // Constants
  @Test
  public void compileTimeConstantExpressionsAreRetainedOnFields() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public class A {",
        "  public final static int NUMBER = 42;",
        "  final static String MAGIC = \"Now you see me\" + 3;",
        "  public static int foo = 37;",
        "  public static final long NOW = System.currentTimeMillis();",
        "}"));

    assertTrue(summary, summary.contains("public static final int NUMBER = 42"));
    assertTrue(summary, summary.contains("static final java.lang.String MAGIC = Now you see me3"));
    assertFalse(summary, summary.contains("public static int foo = 37"));
    assertTrue(summary, summary.contains("public static final long NOW\n"));
  }

  @Test
  public void checkThatDefinedInOtherClassesThatCanBeInlinedAreInlined() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public class A {",
        "  public final static int NUMBER = 42;",
        "  final static String MAGIC = \"Now you see me\" + 3;",
        "  public static int foo = 37;",
        "  public static final long NOW = System.currentTimeMillis();",
        "}"),
        "B.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public class B {",
        "  public final static int KEY = A.NUMBER + 3;",
        "}"));

    assertTrue(summary, summary.contains("KEY = 45"));
  }

  // Enums
  @Test
  public void enumsAreHandled() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public enum A {",
        "  ONE, TWO, THREE;",
        "  public void doSomething() {} ",
        "}"));

    assertTrue(summary, summary.contains("ONE"));
    assertTrue(summary, summary.contains("TWO"));
    assertTrue(summary, summary.contains("THREE"));
    assertTrue(summary, summary.contains("public void doSomething()"));
  }

  @Test
  public void orderingOfEnumConstantsMatters() throws IOException {
    String first = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public enum A {",
        "  ONE, TWO;",
        "  public void doSomething() {} ",
        "}"));

    String second = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public enum A {",
        "  TWO, ONE;",
        "  public void doSomething() {} ",
        "}"));

    assertNotEquals(first, second);
  }

  @Test
  public void canHandleNewAnnotationTypes() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "import java.lang.annotation.*;",
        "import static java.lang.annotation.ElementType.*;",
        "@Retention(RetentionPolicy.RUNTIME)",
        "@Target(value={CONSTRUCTOR, FIELD})",
        "public @interface A {}"));

    assertTrue(summary, summary.contains("@interface com.facebook.buck.example.A"));
    assertTrue(summary, summary.contains(
        "@java.lang.annotation.Retention(value=java.lang.annotation.RetentionPolicy.RUNTIME)"));
    assertTrue(summary, summary.contains(
        "@java.lang.annotation.Target(value={java.lang.annotation.ElementType.CONSTRUCTOR,"));
  }


  @Test
  public void innerClassesCanBeNested() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public class A {",
        "  public static class B {",
        "    public void foo() {}",
        "  }",
        "}"));

    assertTrue(summary, summary.contains(
        "public static class com.facebook.buck.example.A.B extends java.lang.Object"));
  }

  @Test
  public void innerClassesCanBeDeeplyNested() throws IOException {
    String summary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public class A {",
        "  public static class B {",
        "    public class C {",
        "      public void foo() {}",
        "    }",
        "  }",
        "}"));

    assertTrue(summary,
        summary.contains("public class com.facebook.buck.example.A.B.C extends java.lang.Object"));
  }

  @Test
  public void abisOfAClassAndAInterfaceWithSharedMethodsAreNotTheSame() throws IOException {
    String classSummary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public abstract class A {",
        "  public abstract void doSomething();",
        "}"));

    String interfaceSummary = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public interface A {",
        "  void doSomething();",
        "}"));

    assertNotEquals(classSummary, interfaceSummary);
  }

  @Test
  public void reorderingFieldsDoesNotModifyTheAbi() throws IOException {
    String original = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public class A {",
        "  public int first;",
        "  public int second;",
        "}"));

    String abiCompatible = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public class A {",
        "  public int second;",
        "  public int first;",
        "}"));

    assertEquals(original, abiCompatible);
  }

  @Test
  public void reorderingMethodsDoesNotModifyTheAbi() throws IOException {
    String original = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public class A {",
        "  public void doSomething() {}",
        "  public int aLongRunningComputation() { return 42; }",
        "}"));

    String abiCompatible = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public class A {",
        "  public int aLongRunningComputation() { return 2345; }",
        "  public void doSomething() {}",
        "}"));

    assertEquals(original, abiCompatible);
  }

  @Test
  public void renamingMethodArgumentsDoesNotModifyTheAbi() throws IOException {
    String original = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public class A {",
        "  public void doSomething(int count) {}",
        "}"));

    String abiCompatible = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public class A {",
        "  public void doSomething(int numberOfTimes) {}",
        "}"));

    assertEquals(original, abiCompatible);
  }

  @Test
  public void varargsAreNotTheSameAsArrays() throws IOException {
    String original = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public class A {",
        "  public void doSomething(String... args) {}",
        "}"));

    String amended = compile("A.java", Joiner.on("\n").join(
        "package com.facebook.buck.example;",
        "public class A {",
        "  public void doSomething(String[] args) {}",
        "}"));

    assertNotEquals(original, amended);
  }

  @Test
  public void generateSampleOutput() throws IOException {
    File testDataDir = TestDataHelper.getTestDataScenario(this, "compute_abi");
    String source = Files.toString(
        new File(testDataDir, "generateSampleOutput.src"), UTF_8);
    String original = compile("A.java", source);

    String expected = Files.toString(
        new File(testDataDir, "generateSampleOutput.expected"), UTF_8);

    assertEquals(expected, original);
  }

  @Test
  public void generatedHashMustBeZeroPaddedToFortyCharacters() {
    String hex = AbiWriter.computeAbiKey(ImmutableSortedSet.of("abcdefghi"));

    assertEquals('0', hex.charAt(0));
    assertEquals(40, hex.length());
  }

  @Test
  public void shouldIgnoreAnnotatedPackageDeclarations() throws IOException {
    Optional<String> compiled = getOptionalSummary(
        "package-info.java",
        Joiner.on("\n").join(
            "/** This is a documented pacakge */",
            "@Deprecated",  // It's legit to deprecate an entire package
            "package com.facebook.buck.example;"));

    // If we get this far, then we know that everything is just fine. We're just preventing an
    // exception being thrown, and it's impossible to accidentally have a java class called
    // "package-info" so we're not expecting this to contribute to the API.
    assertFalse(compiled.isPresent());
  }

  @Test
  public void shouldIgnoreUnannotatedPackageDeclarations() throws IOException {
    Optional<String> compiled = getOptionalSummary(
        "package-info.java",
        Joiner.on("\n").join(
            "/** This is a documented pacakge without an annotation */",
            "package com.facebook.buck.example;"));

    assertFalse(compiled.isPresent());
  }

  @Test
  public void emptySummariesLeadToAnEmptyAbiKeyBeingMade() throws IOException {
    File outDir = temp.newFolder();
    File onlyDocs = new File(outDir, "package-info.java");
    SortedSet<String> summaries = generateSummary(
        outDir,
        new FileAndSource(onlyDocs,
            Joiner.on("\n").join(
                "/** This is a package */",
                "package com.facebook.buck.example;")),
        ImmutableSet.<File>of());

    assertTrue(summaries.isEmpty());

    String computed = AbiWriter.computeAbiKey(summaries);

    assertEquals(AbiWriterProtocol.EMPTY_ABI_KEY, computed);
  }

  @Test
  public void ensureHashMatchesGuavaEquivalent() {
    String key = AbiWriter.computeAbiKey(ImmutableSortedSet.<String>of());
    String guava = hashWithGuava(ImmutableSortedSet.<String>of());
    assertEquals(guava, key);

    String javaCode = Joiner.on('\n').join(
        "public class Example",
        "public void cheese(java.lang.String)");

    SortedSet<String> summaries = ImmutableSortedSet.of(javaCode);
    key = AbiWriter.computeAbiKey(summaries);
    guava = hashWithGuava(summaries);
    assertEquals(guava, key);
  }

  private String hashWithGuava(SortedSet<String> summaries) {
    Hasher hasher = Hashing.sha1().newHasher();
    for (String summary : summaries) {
      hasher.putUnencodedChars(summary);
    }
    return hasher.hash().toString();
  }

  /**
   * Files are compiled in the order that they're fed to this method. As each file is compiled its
   * output directory is added to a classpath that lives for the duration of the method call. As
   * each file is compiled, the summaries generated by AbiWriter for the last compilation are
   * stored.
   *
   * @return the summary of the last file to be compiled.
   */
  private String compile(
      String fileName, String source, String... fileNamesAndSources) throws IOException {
    return getOptionalSummary(fileName, source, fileNamesAndSources).get();
  }

  private Optional<String> getOptionalSummary(
      String fileName, String source, String... fileNamesAndSources) throws IOException {
    List<FileAndSource> targets = Lists.newArrayList();

    targets.add(new FileAndSource(new File(temp.newFolder(), fileName), source));
    for (int i = 0; i < fileNamesAndSources.length; i++) {
      targets.add(new FileAndSource(
          new File(temp.newFolder(), fileNamesAndSources[i++]), fileNamesAndSources[i]));
    }

    ImmutableSet.Builder<File> classpath = ImmutableSet.builder();
    SortedSet<String> lastSummary = null;
    for (FileAndSource target : targets) {
      File outputDir = temp.newFolder();
      lastSummary = generateSummary(outputDir, target, classpath.build());
      classpath.add(outputDir);
    }
    if (lastSummary.isEmpty()) {
      return Optional.absent();
    }
    return Optional.of(Iterables.getOnlyElement(lastSummary));
  }


  private SortedSet<String> generateSummary(
      File outputDir,
      FileAndSource target,
      ImmutableSet<File> classpath) throws IOException{
    File file = target.file;
    Files.write(target.source, file, UTF_8);

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
    Iterable<? extends JavaFileObject> sourceObjects =
        fileManager.getJavaFileObjectsFromFiles(ImmutableSet.of(file));

    List<String> args = Lists.newArrayList("-g", "-d", outputDir.getAbsolutePath());
    if (!classpath.isEmpty()) {
      args.add("-classpath");
      args.add(Joiner.on(File.pathSeparator).join(classpath));
    }

    AbiWriter processor = new AbiWriter();

    JavaCompiler.CompilationTask compilation =
        compiler.getTask(null, fileManager, null, args, null, sourceObjects);
    compilation.setProcessors(ImmutableSet.of(processor));

    Boolean result = compilation.call();
    assertNotNull(result);
    assertTrue(result);

    return processor.getSummaries();
  }

  private static class FileAndSource {
    public File file;
    public String source;

    public FileAndSource(File file, String source) {
      this.file = file;
      this.source = source;
    }
  }
}
