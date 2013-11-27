/*
 * Copyright 2012-present Facebook, Inc.
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

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;

import com.facebook.buck.android.DummyRDotJava;
import com.facebook.buck.android.JavaLibraryGraphEnhancer;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.LabelsAttributeBuilder;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TargetDevice;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.XmlTestResultParser;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.ZipFileTraversal;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nullable;

public class JavaTestRule extends DefaultJavaLibraryRule implements TestRule {

  private final ImmutableList<String> vmArgs;

  /**
   * Build rules for which this test rule will be testing.
   */
  private final ImmutableSet<JavaLibraryRule> sourceUnderTest;

  private CompiledClassFileFinder compiledClassFileFinder;

  private final ImmutableSet<String> labels;

  private final ImmutableSet<String> contacts;

  protected JavaTestRule(BuildRuleParams buildRuleParams,
      Set<String> srcs,
      Set<SourcePath> resources,
      Optional<DummyRDotJava> optionalDummyRDotJava,
      Set<String> labels,
      Set<String> contacts,
      Optional<String> proguardConfig,
      JavacOptions javacOptions,
      List<String> vmArgs,
      ImmutableSet<JavaLibraryRule> sourceUnderTest) {
    super(buildRuleParams,
        srcs,
        resources,
        optionalDummyRDotJava,
        proguardConfig,
        /* exportDeps */ ImmutableSortedSet.<BuildRule>of(),
        javacOptions);
    this.vmArgs = ImmutableList.copyOf(vmArgs);
    this.sourceUnderTest = Preconditions.checkNotNull(sourceUnderTest);
    this.labels = ImmutableSet.copyOf(labels);
    this.contacts = ImmutableSet.copyOf(contacts);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.JAVA_TEST;
  }

  @Override
  public ImmutableSet<String> getLabels() {
    return labels;
  }

  @Override
  public ImmutableSet<String> getContacts() {
    return contacts;
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) throws IOException {
    ImmutableSortedSet<? extends BuildRule> srcUnderTest = ImmutableSortedSet.copyOf(
        sourceUnderTest);
    super.appendToRuleKey(builder)
        .set("vmArgs", vmArgs)
        .set("sourceUnderTest", srcUnderTest);
    return builder;
  }

  /**
   * @return A set of rules that this test rule will be testing.
   */
  public ImmutableSet<JavaLibraryRule> getSourceUnderTest() {
    return sourceUnderTest;
  }

  public ImmutableList<String> getVmArgs() {
    return vmArgs;
  }

  /**
   * Runs the tests specified by the "srcs" of this class. If this rule transitively depends on
   * other {@code java_test()} rules, then they will be run separately.
   */
  @Override
  public List<Step> runTests(BuildContext buildContext, ExecutionContext executionContext) {
    Preconditions.checkState(isRuleBuilt(), "%s must be built before tests can be run.", this);

    // If no classes were generated, then this is probably a java_test() that declares a number of
    // other java_test() rules as deps, functioning as a test suite. In this case, simply return an
    // empty list of commands.
    Set<String> testClassNames = getClassNamesForSources(executionContext);
    if (testClassNames.isEmpty()) {
      return ImmutableList.of();
    }

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    Path pathToTestOutput = getPathToTestOutputDirectory();
    MakeCleanDirectoryStep mkdirClean = new MakeCleanDirectoryStep(pathToTestOutput);
    steps.add(mkdirClean);

    // If there are android resources, then compile the uber R.java files and add them to the
    // classpath used to run the test runner.
    ImmutableSet<String> classpathEntries;
    if (getProperties().is(ANDROID)) {
      Preconditions.checkState(optionalDummyRDotJava.isPresent(),
          "DummyRDotJava must have been created by the BuildRuleBuilder!");
      String rDotJavaClasspathEntry = optionalDummyRDotJava.get().getRDotJavaBinFolder();
      ImmutableSet.Builder<String> classpathEntriesBuilder = ImmutableSet.builder();
      classpathEntriesBuilder.add(rDotJavaClasspathEntry);
      classpathEntriesBuilder.addAll(getTransitiveClasspathEntries().values());
      classpathEntries = classpathEntriesBuilder.build();
    } else {
      classpathEntries = ImmutableSet.copyOf(getTransitiveClasspathEntries().values());
    }

    Step junit = new JUnitStep(
        classpathEntries,
        testClassNames,
        amendVmArgs(vmArgs, executionContext.getTargetDeviceOptional()),
        pathToTestOutput.toString(),
        executionContext.isCodeCoverageEnabled(),
        executionContext.isJacocoEnabled(),
        executionContext.isDebugEnabled());
    steps.add(junit);

    return steps.build();
  }

  @VisibleForTesting
  List<String> amendVmArgs(List<String> existingVmArgs, Optional<TargetDevice> targetDevice) {
    ImmutableList.Builder<String> vmArgs = ImmutableList.builder();
    vmArgs.addAll(existingVmArgs);
    onAmendVmArgs(vmArgs, targetDevice);
    return vmArgs.build();
  }

  /**
   * Override this method if you need to amend vm args. Subclasses are required
   * to call super.onAmendVmArgs(...).
   */
  protected void onAmendVmArgs(ImmutableList.Builder<String> vmArgsBuilder,
                               Optional<TargetDevice> targetDevice) {
    if (!targetDevice.isPresent()) {
      return;
    }

    if (targetDevice.isPresent()) {
      TargetDevice device = targetDevice.get();
      if (device.isEmulator()) {
        vmArgsBuilder.add("-Dbuck.device=emulator");
      } else {
        vmArgsBuilder.add("-Dbuck.device=device");
      }
      if (device.hasIdentifier()) {
        vmArgsBuilder.add("-Dbuck.device.id=" + device.getIdentifier());
      }
    }
  }

  @Override
  public boolean hasTestResultFiles(ExecutionContext executionContext) {
    // It is possible that this rule was not responsible for running any tests because all tests
    // were run by its deps. In this case, return an empty TestResults.
    Set<String> testClassNames = getClassNamesForSources(executionContext);
    if (testClassNames.isEmpty()) {
      return true;
    }

    File outputDirectory = executionContext.getProjectFilesystem().getFileForRelativePath(
        getPathToTestOutputDirectory());
    for (String testClass : testClassNames) {
      File testResultFile = new File(outputDirectory, testClass + ".xml");
      if (!testResultFile.isFile()) {
        return false;
      }
    }

    return true;
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    return Paths.get(
        BuckConstant.GEN_DIR,
        getBuildTarget().getBaseNameWithSlash(),
        String.format("__java_test_%s_output__", getBuildTarget().getShortName())
    );
  }

  @Override
  public Callable<TestResults> interpretTestResults(final ExecutionContext context) {
    final ImmutableSet<String> contacts = getContacts();
    return new Callable<TestResults>() {

      @Override
      public TestResults call() throws Exception {
        // It is possible that this rule was not responsible for running any tests because all tests
        // were run by its deps. In this case, return an empty TestResults.
        Set<String> testClassNames = getClassNamesForSources(context);
        if (testClassNames.isEmpty()) {
          return new TestResults(getBuildTarget(), ImmutableList.<TestCaseSummary>of(), contacts);
        }

        List<TestCaseSummary> summaries = Lists.newArrayListWithCapacity(testClassNames.size());
        ProjectFilesystem filesystem = context.getProjectFilesystem();
        for (String testClass : testClassNames) {
          File testResultFile = filesystem.getFileForRelativePath(
              getPathToTestOutputDirectory().resolve(String.format("%s.xml", testClass)));
          TestCaseSummary summary = XmlTestResultParser.parse(testResultFile);
          summaries.add(summary);
        }

        return new TestResults(getBuildTarget(), summaries, contacts);
      }

    };
  }

  private Set<String> getClassNamesForSources(ExecutionContext context) {
    if (compiledClassFileFinder == null) {
      compiledClassFileFinder = new CompiledClassFileFinder(this, context);
    }
    return compiledClassFileFinder.getClassNamesForSources();
  }

  @VisibleForTesting
  static class CompiledClassFileFinder {

    private final Set<String> classNamesForSources;

    CompiledClassFileFinder(JavaTestRule rule, ExecutionContext context) {
      Preconditions.checkState(rule.isRuleBuilt(),
          "Rule must be built so that the classes folder is available");
      Path outputPath;
      String relativeOutputPath = rule.getPathToOutputFile();
      if (relativeOutputPath != null) {
        outputPath = context.getProjectFilesystem().getPathRelativizer().apply(relativeOutputPath);
      } else {
        outputPath = null;
      }
      classNamesForSources = getClassNamesForSources(rule.getJavaSrcs(), outputPath);
    }

    public Set<String> getClassNamesForSources() {
      return classNamesForSources;
    }

    /**
     * When a collection of .java files is compiled into a directory, that directory will have a
     * subfolder structure that matches the package structure of the input .java files. In general,
     * the .java files will be 1:1 with the .class files with two notable exceptions:
     * (1) There will be an additional .class file for each inner/anonymous class generated. These
     *     types of classes are easy to identify because they will contain a '$' in the name.
     * (2) A .java file that defines multiple top-level classes (yes, this can exist:
     *     http://stackoverflow.com/questions/2336692/java-multiple-class-declarations-in-one-file)
     *     will generate multiple .class files that do not have '$' in the name.
     * In this method, we perform a strict check for (1) and use a heuristic for (2). It is possible
     * to filter out the type (2) situation with a stricter check that aligns the package directories
     * of the .java files and the .class files, but it is a pain to implement. If this heuristic turns
     * out to be insufficient in practice, then we can fix it.
     *
     * @param sources paths to .java source files that were passed to javac
     * @param jarFile jar where the generated .class files were written
     */
    @VisibleForTesting
    static Set<String> getClassNamesForSources(Set<String> sources, @Nullable Path jarFile) {
      if (jarFile == null) {
        return ImmutableSet.of();
      }

      final Set<String> sourceClassNames = Sets.newHashSetWithExpectedSize(sources.size());
      for (String source : sources) {
        int lastSlashIndex = source.lastIndexOf('/');
        if (lastSlashIndex >= 0) {
          source = source.substring(lastSlashIndex + 1);
        }
        source = source.substring(0, source.length() - ".java".length());
        sourceClassNames.add(source);
      }

      final ImmutableSet.Builder<String> testClassNames = ImmutableSet.builder();
      ZipFileTraversal traversal = new ZipFileTraversal(jarFile.toFile()) {

        @Override
        public void visit(ZipFile zipFile, ZipEntry zipEntry) {
          final String name = new File(zipEntry.getName()).getName();

          // Ignore non-.class files.
          if (!name.endsWith(".class")) {
            return;
          }

          // As a heuristic for case (2) as described in the Javadoc, make sure the name of the .class
          // file matches the name of a .java file.
          String nameWithoutDotClass = name.substring(0, name.length() - ".class".length());
          if (!sourceClassNames.contains(nameWithoutDotClass)) {
            return;
          }

          // Make sure it is a .class file that corresponds to a top-level .class file and not an
          // inner class.
          if (!name.contains("$")) {
            String fullyQualifiedNameWithDotClassSuffix = zipEntry.getName().replace('/', '.');
            String className = fullyQualifiedNameWithDotClassSuffix
                .substring(0, fullyQualifiedNameWithDotClassSuffix.length() - ".class".length());
            testClassNames.add(className);
          }
        }
      };
      try {
        traversal.traverse();
      } catch (IOException e) {
        // There's nothing sane to do here. The jar file really should exist.
        throw Throwables.propagate(e);
      }

      return testClassNames.build();
    }
  }

  public static Builder newJavaTestRuleBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends DefaultJavaLibraryRule.Builder
      implements LabelsAttributeBuilder {

    @Nullable protected List<String> vmArgs = ImmutableList.of();
    protected ImmutableSet<BuildTarget> sourcesUnderTest = ImmutableSet.of();
    protected ImmutableSet<String> labels = ImmutableSet.of();
    protected ImmutableSet<String> contacts = ImmutableSet.of();

    protected Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public JavaTestRule build(BuildRuleResolver ruleResolver) {
      ImmutableSet<JavaLibraryRule> sourceUnderTest = generateSourceUnderTest(sourcesUnderTest,
          ruleResolver);
      AnnotationProcessingParams processingParams = getAnnotationProcessingBuilder().build(ruleResolver);
      javacOptions.setAnnotationProcessingData(processingParams);

      BuildRuleParams buildRuleParams = createBuildRuleParams(ruleResolver);

      JavaLibraryGraphEnhancer.Result result =
          new JavaLibraryGraphEnhancer(buildTarget, buildRuleParams, params)
              .createBuildableForAndroidResources(
                  ruleResolver, /* createBuildableIfEmptyDeps */ false);

      return new JavaTestRule(result.getBuildRuleParams(),
          srcs,
          resources,
          result.getOptionalDummyRDotJava(),
          labels,
          contacts,
          proguardConfig,
          javacOptions.build(),
          vmArgs,
          sourceUnderTest);
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    @Override
    public Builder addDep(BuildTarget dep) {
      super.addDep(dep);
      return this;
    }

    @Override
    public Builder addSrc(String src) {
      super.addSrc(src);
      return this;
    }

    public Builder setVmArgs(List<String> vmArgs) {
      this.vmArgs = ImmutableList.copyOf(vmArgs);
      return this;
    }

    public Builder setSourceUnderTest(ImmutableSet<BuildTarget> sourceUnderTestNames) {
      this.sourcesUnderTest = sourceUnderTestNames;
      return this;
    }

    @Override
    public Builder setLabels(ImmutableSet<String> labels) {
      this.labels = labels;
      return this;
    }

    public Builder setContacts(ImmutableSet<String> contacts) {
      this.contacts = contacts;
      return this;
    }

    /**
     * Generates the set of build rules that contain the source that will be under test.
     */
    protected ImmutableSet<JavaLibraryRule> generateSourceUnderTest(
        ImmutableSet<BuildTarget> sourceUnderTestNames, BuildRuleResolver ruleResolver) {
      ImmutableSet.Builder<JavaLibraryRule> sourceUnderTest = ImmutableSet.builder();
      for (BuildTarget sourceUnderTestName : sourceUnderTestNames) {
        // Generates the set by matching its path with the full path names that are passed in.
        BuildRule rule = ruleResolver.get(sourceUnderTestName);

        if (rule instanceof JavaLibraryRule) {
          sourceUnderTest.add((JavaLibraryRule) rule);
        } else if (rule == null) {
          throw new HumanReadableException(
              "Specified source under test for %s is not among its dependencies: %s",
              getBuildTarget().getFullyQualifiedName(),
              sourceUnderTestName);
        } else {
          // In this case, the source under test specified in the build file was not a Java library
          // rule. Since EMMA requires the sources to be in Java, we will throw this exception and
          // not continue with the tests.
          throw new HumanReadableException(
              "Specified source under test for %s is not a Java library: %s (%s).",
              getBuildTarget().getFullyQualifiedName(),
              rule.getFullyQualifiedName(),
              rule.getType().getName());
        }
      }

      return sourceUnderTest.build();
    }
  }
}
