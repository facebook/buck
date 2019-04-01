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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.Javac.Invocation;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.MockClassLoader;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.SourceVersion;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class Jsr199JavacIntegrationTest {

  public static final ImmutableSortedSet<Path> SOURCE_PATHS =
      ImmutableSortedSet.of(Paths.get("Example.java"));
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private Path pathToSrcsList;

  @Before
  public void setUp() {
    pathToSrcsList = tmp.getRoot().resolve("srcs_list");
  }

  @Test
  public void testGetDescription() throws IOException {
    Jsr199Javac javac = createJavac(/* withSyntaxError */ false);
    String pathToOutputDir = tmp.getRoot().resolve("out").toAbsolutePath().toString();

    assertEquals(
        String.format(
            "javac -source %s -target %s -g " + "-d %s " + "-classpath '' " + "@" + pathToSrcsList,
            AbstractJavacLanguageLevelOptions.TARGETED_JAVA_VERSION,
            AbstractJavacLanguageLevelOptions.TARGETED_JAVA_VERSION,
            pathToOutputDir),
        javac.getDescription(
            ImmutableList.of(
                "-source",
                AbstractJavacLanguageLevelOptions.TARGETED_JAVA_VERSION,
                "-target",
                AbstractJavacLanguageLevelOptions.TARGETED_JAVA_VERSION,
                "-g",
                "-d",
                pathToOutputDir,
                "-classpath",
                "''"),
            SOURCE_PATHS,
            pathToSrcsList));
  }

  @Test
  public void testGetShortName() throws IOException {
    Jsr199Javac javac = createJavac(/* withSyntaxError */ false);
    assertEquals("javac", javac.getShortName());
  }

  @Test
  public void testClassesFile() throws IOException, InterruptedException {
    Jsr199Javac javac = createJavac(/* withSyntaxError */ false);
    ExecutionContext executionContext = TestExecutionContext.newInstance();
    JavacExecutionContext javacExecutionContext =
        JavacExecutionContext.of(
            new JavacEventSinkToBuckEventBusBridge(executionContext.getBuckEventBus()),
            executionContext.getStdErr(),
            executionContext.getClassLoaderCache(),
            executionContext.getVerbosity(),
            executionContext.getCellPathResolver(),
            executionContext.getJavaPackageFinder(),
            createProjectFilesystem(),
            executionContext.getProjectFilesystemFactory(),
            executionContext.getEnvironment(),
            executionContext.getProcessExecutor());

    int exitCode =
        javac
            .newBuildInvocation(
                javacExecutionContext,
                DefaultSourcePathResolver.from(
                    new SourcePathRuleFinder(new TestActionGraphBuilder())),
                BuildTargetFactory.newInstance("//some:example"),
                ImmutableList.of(),
                ImmutableList.of(),
                SOURCE_PATHS,
                pathToSrcsList,
                Paths.get("working"),
                false,
                false,
                null,
                null,
                AbiGenerationMode.CLASS,
                AbiGenerationMode.CLASS,
                null)
            .buildClasses();
    assertEquals("javac should exit with code 0.", exitCode, 0);

    assertTrue(Files.exists(pathToSrcsList));
    assertTrue(Files.isRegularFile(pathToSrcsList));
    assertEquals(
        "Example.java",
        new String(Files.readAllBytes(pathToSrcsList), StandardCharsets.UTF_8).trim());
  }

  /**
   * There was a bug where `BuildTargetSourcePath` sources were written to the classes file using
   * their string representation, rather than their resolved path.
   */
  @Test
  public void shouldWriteResolvedBuildTargetSourcePathsToClassesFile()
      throws IOException, InterruptedException {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildRule rule = new FakeBuildRule("//:fake");
    graphBuilder.addToIndex(rule);

    Jsr199Javac javac = createJavac(/* withSyntaxError */ false);
    ExecutionContext executionContext = TestExecutionContext.newInstance();
    JavacExecutionContext javacExecutionContext =
        JavacExecutionContext.of(
            new JavacEventSinkToBuckEventBusBridge(executionContext.getBuckEventBus()),
            executionContext.getStdErr(),
            executionContext.getClassLoaderCache(),
            executionContext.getVerbosity(),
            executionContext.getCellPathResolver(),
            executionContext.getJavaPackageFinder(),
            createProjectFilesystem(),
            executionContext.getProjectFilesystemFactory(),
            executionContext.getEnvironment(),
            executionContext.getProcessExecutor());

    int exitCode =
        javac
            .newBuildInvocation(
                javacExecutionContext,
                DefaultSourcePathResolver.from(
                    new SourcePathRuleFinder(new TestActionGraphBuilder())),
                BuildTargetFactory.newInstance("//some:example"),
                ImmutableList.of(),
                ImmutableList.of(),
                SOURCE_PATHS,
                pathToSrcsList,
                Paths.get("working"),
                false,
                false,
                null,
                null,
                AbiGenerationMode.CLASS,
                AbiGenerationMode.CLASS,
                null)
            .buildClasses();
    assertEquals("javac should exit with code 0.", exitCode, 0);

    assertTrue(Files.exists(pathToSrcsList));
    assertTrue(Files.isRegularFile(pathToSrcsList));
    assertEquals(
        "Example.java",
        new String(Files.readAllBytes(pathToSrcsList), StandardCharsets.UTF_8).trim());
  }

  public static final class MockJavac implements JavaCompiler {

    public MockJavac() {}

    @Override
    public Set<SourceVersion> getSourceVersions() {
      return ImmutableSet.of(SourceVersion.RELEASE_7);
    }

    @Override
    public int run(InputStream in, OutputStream out, OutputStream err, String... arguments) {
      throw new OutOfMemoryError("abcdef");
    }

    @Override
    public int isSupportedOption(String option) {
      return -1;
    }

    @Override
    public StandardJavaFileManager getStandardFileManager(
        DiagnosticListener<? super JavaFileObject> diagnosticListener,
        Locale locale,
        Charset charset) {
      throw new OutOfMemoryError("abcdef");
    }

    @Override
    public CompilationTask getTask(
        Writer out,
        JavaFileManager fileManager,
        DiagnosticListener<? super JavaFileObject> diagnosticListener,
        Iterable<String> options,
        Iterable<String> classes,
        Iterable<? extends JavaFileObject> compilationUnits) {
      throw new OutOfMemoryError("abcdef");
    }
  }

  @Test
  public void shouldUseSpecifiedJavacJar() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildRule rule = new FakeBuildRule("//:fake");
    graphBuilder.addToIndex(rule);

    Path fakeJavacJar = Paths.get("ae036e57-77a7-4356-a79c-0f85b1a3290d", "fakeJavac.jar");
    ExecutionContext executionContext = TestExecutionContext.newInstance();
    MockClassLoader mockClassLoader =
        new MockClassLoader(
            ClassLoader.getSystemClassLoader(),
            ImmutableMap.of(
                ExternalJavacProvider.COM_SUN_TOOLS_JAVAC_API_JAVAC_TOOL, MockJavac.class));
    executionContext
        .getClassLoaderCache()
        .injectClassLoader(
            ClassLoader.getSystemClassLoader(),
            ImmutableList.of(fakeJavacJar.toUri().toURL()),
            mockClassLoader);

    Jsr199Javac javac = createJavac(/* withSyntaxError */ false, Optional.of(fakeJavacJar));

    JavacExecutionContext javacExecutionContext =
        JavacExecutionContext.of(
            new JavacEventSinkToBuckEventBusBridge(executionContext.getBuckEventBus()),
            executionContext.getStdErr(),
            executionContext.getClassLoaderCache(),
            executionContext.getVerbosity(),
            executionContext.getCellPathResolver(),
            executionContext.getJavaPackageFinder(),
            createProjectFilesystem(),
            executionContext.getProjectFilesystemFactory(),
            executionContext.getEnvironment(),
            executionContext.getProcessExecutor());

    boolean caught = false;

    try {
      javac
          .newBuildInvocation(
              javacExecutionContext,
              DefaultSourcePathResolver.from(
                  new SourcePathRuleFinder(new TestActionGraphBuilder())),
              BuildTargetFactory.newInstance("//some:example"),
              ImmutableList.of(),
              ImmutableList.of(),
              SOURCE_PATHS,
              pathToSrcsList,
              Paths.get("working"),
              false,
              false,
              null,
              null,
              AbiGenerationMode.CLASS,
              AbiGenerationMode.CLASS,
              null)
          .buildClasses();
      fail("Did not expect compilation to succeed");
    } catch (OutOfMemoryError ex) {
      if (ex.toString().contains("abcdef")) {
        caught = true;
      }
    }

    assertTrue("mock Java compiler should throw", caught);
  }

  private Jsr199Javac createJavac(boolean withSyntaxError, Optional<Path> javacJar)
      throws IOException {

    Path exampleJava = tmp.newFile("Example.java");
    Files.write(
        exampleJava,
        Joiner.on('\n')
            .join(
                "package com.example;", "", "public class Example {" + (withSyntaxError ? "" : "}"))
            .getBytes(Charsets.UTF_8));

    Path pathToOutputDirectory = Paths.get("out");
    tmp.newFolder(pathToOutputDirectory.toString());

    Optional<SourcePath> jar = javacJar.map(p -> PathSourcePath.of(new FakeProjectFilesystem(), p));
    if (jar.isPresent()) {
      return new JarBackedJavac(
          ExternalJavacProvider.COM_SUN_TOOLS_JAVAC_API_JAVAC_TOOL, ImmutableSet.of(jar.get()));
    }

    return new JdkProvidedInMemoryJavac();
  }

  private Jsr199Javac createJavac(boolean withSyntaxError) throws IOException {
    return createJavac(withSyntaxError, Optional.empty());
  }

  private ProjectFilesystem createProjectFilesystem() {
    return TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
  }

  /**
   * Behaves like {@link com.facebook.buck.jvm.java.JdkProvidedInMemoryJavac} when JDK is not
   * present
   */
  private static class JdkNotFoundJavac extends Jsr199Javac {
    @Override
    protected JavaCompiler createCompiler(
        JavacExecutionContext context, SourcePathResolver resolver) {
      throw new RuntimeException("JDK is not found");
    }
  }

  @Test
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void jdkNotFound() {
    Jsr199Javac javac = new JdkNotFoundJavac();
    ExecutionContext executionContext = TestExecutionContext.newInstance();
    JavacExecutionContext javacExecutionContext =
        JavacExecutionContext.of(
            new JavacEventSinkToBuckEventBusBridge(executionContext.getBuckEventBus()),
            executionContext.getStdErr(),
            executionContext.getClassLoaderCache(),
            executionContext.getVerbosity(),
            executionContext.getCellPathResolver(),
            executionContext.getJavaPackageFinder(),
            createProjectFilesystem(),
            executionContext.getProjectFilesystemFactory(),
            executionContext.getEnvironment(),
            executionContext.getProcessExecutor());

    Invocation buildInvocation =
        javac.newBuildInvocation(
            javacExecutionContext,
            DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder())),
            BuildTargetFactory.newInstance("//some:example"),
            ImmutableList.of(),
            ImmutableList.of(),
            SOURCE_PATHS,
            pathToSrcsList,
            Paths.get("working"),
            false,
            false,
            null,
            null,
            AbiGenerationMode.CLASS,
            AbiGenerationMode.CLASS,
            null);
    try {
      buildInvocation.buildClasses();
      fail();
    } catch (Exception e) {
      // expected
    }

    // Make sure `close` works properly
    buildInvocation.close();
  }
}
