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

package com.facebook.buck.android;

import static com.facebook.buck.jvm.java.JavaCompilationConstants.ANDROID_JAVAC_OPTIONS;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.resolver.impl.TestBuildRuleResolver;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.AnnotationProcessingParams;
import com.facebook.buck.jvm.java.ClasspathChecker;
import com.facebook.buck.jvm.java.CompilerParameters;
import com.facebook.buck.jvm.java.ExtraClasspathProvider;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacStep;
import com.facebook.buck.jvm.java.JavacToJarStepFactory;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.Test;

public class DummyRDotJavaTest {
  @Test
  public void testBuildSteps() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver ruleResolver = new TestBuildRuleResolver();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildRule resourceRule1 =
        ruleResolver.addToIndex(
            AndroidResourceRuleBuilder.newBuilder()
                .setRuleFinder(ruleFinder)
                .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res1"))
                .setRDotJavaPackage("com.facebook")
                .setRes(FakeSourcePath.of("android_res/com/example/res1"))
                .build());
    setAndroidResourceBuildOutput(resourceRule1);
    BuildRule resourceRule2 =
        ruleResolver.addToIndex(
            AndroidResourceRuleBuilder.newBuilder()
                .setRuleFinder(ruleFinder)
                .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res2"))
                .setRDotJavaPackage("com.facebook")
                .setRes(FakeSourcePath.of("android_res/com/example/res2"))
                .build());
    setAndroidResourceBuildOutput(resourceRule2);

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/base:rule");
    DummyRDotJava dummyRDotJava =
        new DummyRDotJava(
            buildTarget,
            filesystem,
            ruleFinder,
            ImmutableSet.of(
                (HasAndroidResourceDeps) resourceRule1, (HasAndroidResourceDeps) resourceRule2),
            new JavacToJarStepFactory(
                pathResolver,
                ruleFinder,
                filesystem,
                DEFAULT_JAVAC,
                ANDROID_JAVAC_OPTIONS,
                ExtraClasspathProvider.EMPTY),
            /* forceFinalResourceIds */ false,
            Optional.empty(),
            Optional.of("R2"),
            /* useOldStyleableFormat */ false,
            /* skipNonUnionRDotJava */ false);

    FakeBuildableContext buildableContext = new FakeBuildableContext();
    List<Step> steps = dummyRDotJava.getBuildSteps(FakeBuildContext.NOOP_CONTEXT, buildableContext);
    assertEquals("DummyRDotJava returns an incorrect number of Steps.", 12, steps.size());

    Path rDotJavaSrcFolder =
        BuildTargets.getScratchPath(
            filesystem, dummyRDotJava.getBuildTarget(), "__%s_rdotjava_src__");
    Path rDotJavaBinFolder =
        BuildTargets.getScratchPath(
            filesystem, dummyRDotJava.getBuildTarget(), "__%s_rdotjava_bin__");
    Path rDotJavaOutputFolder =
        BuildTargets.getGenPath(
            filesystem, dummyRDotJava.getBuildTarget(), "__%s_dummyrdotjava_output__");
    String rDotJavaOutputJar =
        MorePaths.pathWithPlatformSeparators(
            String.format(
                "%s/%s.jar",
                rDotJavaOutputFolder,
                dummyRDotJava.getBuildTarget().getShortNameAndFlavorPostfix()));
    String genFolder = Paths.get("buck-out/gen/java/base/").toString();

    List<String> sortedSymbolsFiles =
        Stream.of((AndroidResource) resourceRule1, (AndroidResource) resourceRule2)
            .map(Object::toString)
            .collect(ImmutableList.toImmutableList());
    ImmutableSortedSet<Path> javaSourceFiles =
        ImmutableSortedSet.of(rDotJavaSrcFolder.resolve("com/facebook/R.java"));

    List<String> expectedStepDescriptions =
        new ImmutableList.Builder<String>()
            .addAll(makeCleanDirDescription(rDotJavaSrcFolder))
            .add("android-res-merge " + Joiner.on(' ').join(sortedSymbolsFiles))
            .add("android-res-merge " + Joiner.on(' ').join(sortedSymbolsFiles))
            .addAll(makeCleanDirDescription(rDotJavaBinFolder))
            .addAll(makeCleanDirDescription(rDotJavaOutputFolder))
            .add(String.format("mkdir -p %s", genFolder))
            .add(
                new JavacStep(
                        DEFAULT_JAVAC,
                        JavacOptions.builder(ANDROID_JAVAC_OPTIONS)
                            .setAnnotationProcessingParams(AnnotationProcessingParams.EMPTY)
                            .build(),
                        dummyRDotJava.getBuildTarget(),
                        pathResolver,
                        new FakeProjectFilesystem(),
                        new ClasspathChecker(),
                        CompilerParameters.builder()
                            .setOutputDirectory(rDotJavaBinFolder)
                            .setGeneratedCodeDirectory(Paths.get("generated"))
                            .setWorkingDirectory(Paths.get("working"))
                            .setSourceFilePaths(javaSourceFiles)
                            .setPathToSourcesList(
                                BuildTargets.getGenPath(
                                    filesystem, dummyRDotJava.getBuildTarget(), "__%s__srcs"))
                            .setClasspathEntries(ImmutableSortedSet.of())
                            .build(),
                        null,
                        null)
                    .getDescription(TestExecutionContext.newInstance()))
            .add(String.format("jar cf %s  %s", rDotJavaOutputJar, rDotJavaBinFolder))
            .add(String.format("check_dummy_r_jar_not_empty %s", rDotJavaOutputJar))
            .build();

    MoreAsserts.assertSteps(
        "DummyRDotJava.getBuildSteps() must return these exact steps.",
        expectedStepDescriptions,
        steps,
        TestExecutionContext.newInstance());

    assertEquals(
        ImmutableSet.of(rDotJavaBinFolder, Paths.get(rDotJavaOutputJar)),
        buildableContext.getRecordedArtifacts());
  }

  @Test
  public void testRDotJavaBinFolder() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestBuildRuleResolver());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/com/example:library");
    DummyRDotJava dummyRDotJava =
        new DummyRDotJava(
            buildTarget,
            new FakeProjectFilesystem(),
            ruleFinder,
            ImmutableSet.of(),
            new JavacToJarStepFactory(
                pathResolver,
                ruleFinder,
                filesystem,
                DEFAULT_JAVAC,
                ANDROID_JAVAC_OPTIONS,
                ExtraClasspathProvider.EMPTY),
            /* forceFinalResourceIds */ false,
            Optional.empty(),
            Optional.empty(),
            /* useOldStyleableFormat */ false,
            /* skipNonUnionRDotJava */ false);
    assertEquals(
        BuildTargets.getScratchPath(
            dummyRDotJava.getProjectFilesystem(),
            dummyRDotJava.getBuildTarget(),
            "__%s_rdotjava_bin__"),
        dummyRDotJava.getRDotJavaBinFolder());
  }

  private static ImmutableList<String> makeCleanDirDescription(Path dirname) {
    return ImmutableList.of(
        String.format("rm -f -r %s", dirname), String.format("mkdir -p %s", dirname));
  }

  private void setAndroidResourceBuildOutput(BuildRule resourceRule) {
    if (resourceRule instanceof AndroidResource) {
      ((AndroidResource) resourceRule).getBuildOutputInitializer();
    }
  }
}
