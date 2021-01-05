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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.javacd.model.BaseJarCommand.AbiGenerationMode;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.java.ResolvedJavac.Invocation;
import com.facebook.buck.jvm.java.version.JavaVersion;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.MockClassLoader;
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
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class Jsr199JavacIntegrationTest {

  public static final ImmutableSortedSet<RelPath> SOURCE_PATHS =
      ImmutableSortedSet.orderedBy(RelPath.comparator()).add(RelPath.get("Example.java")).build();
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private RelPath pathToSrcsList;
  private AbsPath pathToSrcsListAbsPath;
  private BuildTarget buildTarget;
  private BuildTargetValue buildTargetValue;
  private ProjectFilesystem projectFilesystem;

  @Before
  public void setUp() {
    pathToSrcsList = RelPath.get("srcs_list");
    pathToSrcsListAbsPath = tmp.getRoot().resolve(pathToSrcsList);
    buildTarget = BuildTargetFactory.newInstance("//some:example");

    projectFilesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    buildTargetValue = BuildTargetValue.of(buildTarget);
  }

  @Test
  public void testGetDescription() throws IOException {
    Jsr199Javac.ResolvedJsr199Javac javac = createJavac(/* withSyntaxError */ false);
    String pathToOutputDir = tmp.getRoot().resolve("out").toString();

    assertEquals(
        String.format(
            "javac -source %s -target %s -g " + "-d %s " + "-classpath '' " + "@" + pathToSrcsList,
            JavacLanguageLevelOptions.TARGETED_JAVA_VERSION,
            JavacLanguageLevelOptions.TARGETED_JAVA_VERSION,
            pathToOutputDir),
        javac.getDescription(
            ImmutableList.of(
                "-source",
                JavacLanguageLevelOptions.TARGETED_JAVA_VERSION,
                "-target",
                JavacLanguageLevelOptions.TARGETED_JAVA_VERSION,
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
    Jsr199Javac.ResolvedJsr199Javac javac = createJavac(/* withSyntaxError */ false);
    assertEquals("javac", javac.getShortName());
  }

  @Test
  public void testClassesFile() throws IOException, InterruptedException {
    Jsr199Javac.ResolvedJsr199Javac javac = createJavac(/* withSyntaxError */ false);
    StepExecutionContext executionContext = TestExecutionContext.newInstance();

    BuckPaths buckPaths = projectFilesystem.getBuckPaths();
    JavacExecutionContext javacExecutionContext =
        ImmutableJavacExecutionContext.ofImpl(
            new JavacEventSinkToBuckEventBusBridge(executionContext.getBuckEventBus().isolated()),
            executionContext.getStdErr(),
            executionContext.getClassLoaderCache(),
            executionContext.getVerbosity(),
            ImmutableMap.of(),
            projectFilesystem.getRootPath(),
            executionContext.getEnvironment(),
            executionContext.getProcessExecutor(),
            buckPaths.getConfiguredBuckOut());

    int exitCode =
        javac
            .newBuildInvocation(
                javacExecutionContext,
                buildTargetValue,
                CompilerOutputPathsValue.of(buckPaths, buildTarget),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                SOURCE_PATHS,
                pathToSrcsList,
                RelPath.get("working"),
                false,
                false,
                null,
                null,
                AbiGenerationMode.CLASS,
                AbiGenerationMode.CLASS,
                null)
            .buildClasses();
    assertEquals("javac should exit with code 0.", exitCode, 0);

    assertTrue(Files.exists(pathToSrcsListAbsPath.getPath()));
    assertTrue(Files.isRegularFile(pathToSrcsListAbsPath.getPath()));
    assertEquals(
        "Example.java",
        new String(Files.readAllBytes(pathToSrcsListAbsPath.getPath()), StandardCharsets.UTF_8)
            .trim());
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

    Jsr199Javac.ResolvedJsr199Javac javac = createJavac(/* withSyntaxError */ false);
    StepExecutionContext executionContext = TestExecutionContext.newInstance();
    BuckPaths buckPaths = projectFilesystem.getBuckPaths();
    JavacExecutionContext javacExecutionContext =
        ImmutableJavacExecutionContext.ofImpl(
            new JavacEventSinkToBuckEventBusBridge(executionContext.getBuckEventBus().isolated()),
            executionContext.getStdErr(),
            executionContext.getClassLoaderCache(),
            executionContext.getVerbosity(),
            ImmutableMap.of(),
            projectFilesystem.getRootPath(),
            executionContext.getEnvironment(),
            executionContext.getProcessExecutor(),
            buckPaths.getConfiguredBuckOut());

    int exitCode =
        javac
            .newBuildInvocation(
                javacExecutionContext,
                buildTargetValue,
                CompilerOutputPathsValue.of(buckPaths, buildTarget),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                SOURCE_PATHS,
                pathToSrcsList,
                RelPath.get("working"),
                false,
                false,
                null,
                null,
                AbiGenerationMode.CLASS,
                AbiGenerationMode.CLASS,
                null)
            .buildClasses();
    assertEquals("javac should exit with code 0.", exitCode, 0);

    assertTrue(Files.exists(pathToSrcsListAbsPath.getPath()));
    assertTrue(Files.isRegularFile(pathToSrcsListAbsPath.getPath()));
    assertEquals(
        "Example.java",
        new String(Files.readAllBytes(pathToSrcsListAbsPath.getPath()), StandardCharsets.UTF_8)
            .trim());
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
    // TODO(T47912516): Remove or test for expected error message after we've decided how to handle
    //                  javac JARs in Java 11.
    Assume.assumeThat(JavaVersion.getMajorVersion(), Matchers.lessThanOrEqualTo(8));

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildRule rule = new FakeBuildRule("//:fake");
    graphBuilder.addToIndex(rule);

    Path fakeJavacJar = Paths.get("ae036e57-77a7-4356-a79c-0f85b1a3290d", "fakeJavac.jar");
    StepExecutionContext executionContext = TestExecutionContext.newInstance();
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

    Jsr199Javac.ResolvedJsr199Javac javac =
        createJavac(/* withSyntaxError */ false, Optional.of(fakeJavacJar));

    BuckPaths buckPaths = projectFilesystem.getBuckPaths();
    JavacExecutionContext javacExecutionContext =
        ImmutableJavacExecutionContext.ofImpl(
            new JavacEventSinkToBuckEventBusBridge(executionContext.getBuckEventBus().isolated()),
            executionContext.getStdErr(),
            executionContext.getClassLoaderCache(),
            executionContext.getVerbosity(),
            ImmutableMap.of(),
            projectFilesystem.getRootPath(),
            executionContext.getEnvironment(),
            executionContext.getProcessExecutor(),
            buckPaths.getConfiguredBuckOut());

    boolean caught = false;

    try {
      javac
          .newBuildInvocation(
              javacExecutionContext,
              buildTargetValue,
              CompilerOutputPathsValue.of(buckPaths, buildTarget),
              ImmutableList.of(),
              ImmutableList.of(),
              ImmutableList.of(),
              SOURCE_PATHS,
              pathToSrcsList,
              RelPath.get("working"),
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

  private Jsr199Javac.ResolvedJsr199Javac createJavac(
      boolean withSyntaxError, Optional<Path> javacJar) throws IOException {

    Path exampleJava = tmp.newFile("Example.java").getPath();
    Files.write(
        exampleJava,
        Joiner.on('\n')
            .join(
                "package com.example;", "", "public class Example {" + (withSyntaxError ? "" : "}"))
            .getBytes(StandardCharsets.UTF_8));

    Path pathToOutputDirectory = Paths.get("out");
    tmp.newFolder(pathToOutputDirectory.toString());

    Optional<SourcePath> jar = javacJar.map(p -> PathSourcePath.of(new FakeProjectFilesystem(), p));
    SourcePathResolverAdapter sourcePathResolver =
        new TestActionGraphBuilder().getSourcePathResolver();
    if (jar.isPresent()) {
      return new JarBackedJavac(
              ExternalJavacProvider.COM_SUN_TOOLS_JAVAC_API_JAVAC_TOOL, ImmutableSet.of(jar.get()))
          .resolve(sourcePathResolver, projectFilesystem.getRootPath());
    }

    return new JdkProvidedInMemoryJavac()
        .resolve(sourcePathResolver, projectFilesystem.getRootPath());
  }

  private Jsr199Javac.ResolvedJsr199Javac createJavac(boolean withSyntaxError) throws IOException {
    return createJavac(withSyntaxError, Optional.empty());
  }

  /**
   * Behaves like {@link com.facebook.buck.jvm.java.JdkProvidedInMemoryJavac} when JDK is not
   * present
   */
  private static class JdkNotFoundJavac extends Jsr199Javac {

    @Override
    protected ResolvedJsr199Javac create(SourcePathResolverAdapter resolver, AbsPath ruleCellRoot) {
      return new ResolvedJsr199Javac() {

        @Override
        protected JavaCompiler createCompiler(JavacExecutionContext context) {
          throw new RuntimeException("JDK is not found");
        }
      };
    }
  }

  @Test
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void jdkNotFound() {
    Jsr199Javac.ResolvedJsr199Javac javac =
        new JdkNotFoundJavac()
            .resolve(
                new TestActionGraphBuilder().getSourcePathResolver(),
                projectFilesystem.getRootPath());
    StepExecutionContext executionContext = TestExecutionContext.newInstance();
    BuckPaths buckPaths = projectFilesystem.getBuckPaths();
    JavacExecutionContext javacExecutionContext =
        ImmutableJavacExecutionContext.ofImpl(
            new JavacEventSinkToBuckEventBusBridge(executionContext.getBuckEventBus().isolated()),
            executionContext.getStdErr(),
            executionContext.getClassLoaderCache(),
            executionContext.getVerbosity(),
            ImmutableMap.of(),
            projectFilesystem.getRootPath(),
            executionContext.getEnvironment(),
            executionContext.getProcessExecutor(),
            buckPaths.getConfiguredBuckOut());

    Invocation buildInvocation =
        javac.newBuildInvocation(
            javacExecutionContext,
            buildTargetValue,
            CompilerOutputPathsValue.of(buckPaths, buildTarget),
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            SOURCE_PATHS,
            pathToSrcsList,
            RelPath.get("working"),
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
