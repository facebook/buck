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

package com.facebook.buck.java;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.facebook.buck.util.MockClassLoader;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;
import javax.lang.model.SourceVersion;
import java.nio.charset.Charset;
import java.io.Writer;
import java.util.Locale;
import java.util.Set;
import java.io.InputStream;
import java.io.OutputStream;

public class Jsr199JavacIntegrationTest {
  public static final ImmutableSet<Path> SOURCE_PATHS = ImmutableSet.of(Paths.get("Example.java"));
  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  private Path pathToSrcsList;

  @Before
  public void setUp() {
    pathToSrcsList = Paths.get(tmp.getRoot().getPath(), "srcs_list");
  }

  @Test
  public void testGetDescription() throws IOException {
    Jsr199Javac javac = createJavac(/* withSyntaxError */ false);
    ExecutionContext executionContext = createExecutionContext();
    String pathToOutputDir = new File(tmp.getRoot(), "out").getAbsolutePath();

    assertEquals(
        String.format("javac -source %s -target %s -g " +
            "-d %s " +
            "-classpath '' " +
            "@" + pathToSrcsList.toString(),
            JavaBuckConfig.TARGETED_JAVA_VERSION,
            JavaBuckConfig.TARGETED_JAVA_VERSION,
            pathToOutputDir),
        javac.getDescription(
            executionContext,
            ImmutableList.of(
                "-source", JavaBuckConfig.TARGETED_JAVA_VERSION,
                "-target", JavaBuckConfig.TARGETED_JAVA_VERSION,
                "-g",
                "-d", pathToOutputDir,
                "-classpath", "''"),
            SOURCE_PATHS,
            Optional.of(pathToSrcsList)));
  }

  @Test
  public void testGetShortName() throws IOException {
    Jsr199Javac javac = createJavac(/* withSyntaxError */ false);
    assertEquals("javac", javac.getShortName());
  }

  @Test
  public void testClassesFile() throws IOException, InterruptedException {
    Jsr199Javac javac = createJavac(/* withSyntaxError */ false);
    ExecutionContext executionContext = createExecutionContext();
    int exitCode = javac.buildWithClasspath(
        executionContext,
        BuildTargetFactory.newInstance("//some:example"),
        ImmutableList.<String>of(),
        SOURCE_PATHS,
        Optional.of(pathToSrcsList),
        Optional.<Path>absent());
    assertEquals("javac should exit with code 0.", exitCode, 0);

    File srcsListFile = pathToSrcsList.toFile();
    assertTrue(srcsListFile.exists());
    assertTrue(srcsListFile.isFile());
    assertEquals("Example.java", Files.toString(srcsListFile, Charsets.UTF_8).trim());
  }

  /**
   * There was a bug where `BuildTargetSourcePath` sources were written to the classes file using
   * their string representation, rather than their resolved path.
   */
  @Test
  public void shouldWriteResolvedBuildTargetSourcePathsToClassesFile()
      throws IOException, InterruptedException {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildRule rule = new FakeBuildRule("//:fake", pathResolver);
    resolver.addToIndex(rule);

    Jsr199Javac javac = createJavac(
        /* withSyntaxError */ false);
    ExecutionContext executionContext = createExecutionContext();
    int exitCode = javac.buildWithClasspath(
        executionContext,
        BuildTargetFactory.newInstance("//some:example"),
        ImmutableList.<String>of(),
        SOURCE_PATHS,
        Optional.of(pathToSrcsList),
        Optional.<Path>absent());
    assertEquals("javac should exit with code 0.", exitCode, 0);

    File srcsListFile = pathToSrcsList.toFile();
    assertTrue(srcsListFile.exists());
    assertTrue(srcsListFile.isFile());
    assertEquals("Example.java", Files.toString(srcsListFile, Charsets.UTF_8).trim());
  }

  public static final class MockJavac implements JavaCompiler {

    public MockJavac() {
    }

    @Override
    public Set<SourceVersion> getSourceVersions() {
      return ImmutableSet.of(SourceVersion.RELEASE_7);
    }

    @Override
    public int run(
        InputStream in,
        OutputStream out,
        OutputStream err,
        String... arguments) {
      throw new UnsupportedOperationException("abcdef");
    }

    @Override
    public int isSupportedOption(String option) {
      return -1;
    }

    @Override
    public StandardJavaFileManager
    getStandardFileManager(
        DiagnosticListener<? super JavaFileObject> diagnosticListener,
        Locale locale,
        Charset charset) {
      throw new UnsupportedOperationException("abcdef");
    }

    @Override
    public CompilationTask getTask(
        Writer out,
        JavaFileManager fileManager,
        DiagnosticListener<? super JavaFileObject> diagnosticListener,
        Iterable<String> options,
        Iterable<String> classes,
        Iterable<? extends JavaFileObject> compilationUnits) {
      throw new UnsupportedOperationException("abcdef");
    }
  }

  @Test
  public void shouldUseSpecifiedJavacJar() throws Exception {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildRule rule = new FakeBuildRule("//:fake", pathResolver);
    resolver.addToIndex(rule);

    Path fakeJavacJar = Paths.get("ae036e57-77a7-4356-a79c-0f85b1a3290d", "fakeJavac.jar");
    ExecutionContext executionContext = createExecutionContext();
    MockClassLoader mockClassLoader = new MockClassLoader(
        ClassLoader.getSystemClassLoader(),
        ImmutableMap.<String, Class<?>>of(
            "com.sun.tools.javac.api.JavacTool",
            MockJavac.class));
    executionContext.getClassLoaderCache().injectClassLoader(
        ClassLoader.getSystemClassLoader(),
        ImmutableList.of(fakeJavacJar),
        mockClassLoader);

    Jsr199Javac javac = createJavac(
        /* withSyntaxError */ false,
        Optional.of(fakeJavacJar));

    boolean caught = false;

    try {
      javac.buildWithClasspath(
          executionContext,
          BuildTargetFactory.newInstance("//some:example"),
          ImmutableList.<String>of(),
          SOURCE_PATHS,
          Optional.of(pathToSrcsList),
          Optional.<Path>absent());
    } catch (UnsupportedOperationException ex) {
      if (ex.toString().contains("abcdef")) {
        caught = true;
      }
    }

    assertTrue("mock Java compiler should throw", caught);
  }

  private Jsr199Javac createJavac(
      boolean withSyntaxError,
      Optional<Path> javacJar) throws IOException {

    File exampleJava = tmp.newFile("Example.java");
    Files.write(Joiner.on('\n').join(
            "package com.example;",
            "",
            "public class Example {" +
            (withSyntaxError ? "" : "}")
        ),
        exampleJava,
        Charsets.UTF_8);

    Path pathToOutputDirectory = Paths.get("out");
    tmp.newFolder(pathToOutputDirectory.toString());
    return new Jsr199Javac(javacJar);
  }

  private Jsr199Javac createJavac(boolean withSyntaxError) throws IOException {
    return createJavac(withSyntaxError, Optional.<Path>absent());
  }

  private ExecutionContext createExecutionContext() {
    return TestExecutionContext.newBuilder()
        .setProjectFilesystem(new ProjectFilesystem(tmp.getRootPath()))
        .build();
  }
}
