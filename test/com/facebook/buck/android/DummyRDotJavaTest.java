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

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.AnnotationProcessingParams;
import com.facebook.buck.jvm.java.ClasspathChecker;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsAmender;
import com.facebook.buck.jvm.java.JavacStep;
import com.facebook.buck.jvm.java.JavacToJarStepFactory;
import com.facebook.buck.jvm.java.NoOpClassUsageFileWriter;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.Test;

public class DummyRDotJavaTest {
  @Test
  public void testBuildSteps() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    BuildRule resourceRule1 =
        ruleResolver.addToIndex(
            AndroidResourceRuleBuilder.newBuilder()
                .setRuleFinder(ruleFinder)
                .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res1"))
                .setRDotJavaPackage("com.facebook")
                .setRes(new FakeSourcePath("android_res/com/example/res1"))
                .build());
    setAndroidResourceBuildOutput(resourceRule1);
    BuildRule resourceRule2 =
        ruleResolver.addToIndex(
            AndroidResourceRuleBuilder.newBuilder()
                .setRuleFinder(ruleFinder)
                .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res2"))
                .setRDotJavaPackage("com.facebook")
                .setRes(new FakeSourcePath("android_res/com/example/res2"))
                .build());
    setAndroidResourceBuildOutput(resourceRule2);

    DummyRDotJava dummyRDotJava =
        new DummyRDotJava(
            new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//java/base:rule"))
                .setProjectFilesystem(filesystem)
                .build(),
            ruleFinder,
            ImmutableSet.of(
                (HasAndroidResourceDeps) resourceRule1, (HasAndroidResourceDeps) resourceRule2),
            new JavacToJarStepFactory(
                DEFAULT_JAVAC, ANDROID_JAVAC_OPTIONS, JavacOptionsAmender.IDENTITY),
            /* forceFinalResourceIds */ false,
            Optional.empty(),
            Optional.of("R2"),
            false);

    FakeBuildableContext buildableContext = new FakeBuildableContext();
    List<Step> steps = dummyRDotJava.getBuildSteps(FakeBuildContext.NOOP_CONTEXT, buildableContext);
    assertEquals("DummyRDotJava returns an incorrect number of Steps.", 12, steps.size());

    String rDotJavaSrcFolder =
        BuildTargets.getScratchPath(
                filesystem, dummyRDotJava.getBuildTarget(), "__%s_rdotjava_src__")
            .toString();
    String rDotJavaBinFolder =
        BuildTargets.getScratchPath(
                filesystem, dummyRDotJava.getBuildTarget(), "__%s_rdotjava_bin__")
            .toString();
    String rDotJavaOutputFolder =
        BuildTargets.getGenPath(
                filesystem, dummyRDotJava.getBuildTarget(), "__%s_dummyrdotjava_output__")
            .toString();
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
            .collect(MoreCollectors.toImmutableList());
    ImmutableSortedSet<Path> javaSourceFiles =
        ImmutableSortedSet.of(Paths.get(rDotJavaSrcFolder).resolve("com/facebook/R.java"));

    List<String> expectedStepDescriptions =
        new ImmutableList.Builder<String>()
            .addAll(makeCleanDirDescription(filesystem.resolve(rDotJavaSrcFolder)))
            .add("android-res-merge " + Joiner.on(' ').join(sortedSymbolsFiles))
            .add("android-res-merge " + Joiner.on(' ').join(sortedSymbolsFiles))
            .addAll(makeCleanDirDescription(filesystem.resolve(rDotJavaBinFolder)))
            .addAll(makeCleanDirDescription(filesystem.resolve(rDotJavaOutputFolder)))
            .add(String.format("mkdir -p %s", filesystem.resolve(genFolder)))
            .add(
                new JavacStep(
                        Paths.get(rDotJavaBinFolder),
                        NoOpClassUsageFileWriter.instance(),
                        Optional.empty(),
                        javaSourceFiles,
                        BuildTargets.getGenPath(
                            filesystem, dummyRDotJava.getBuildTarget(), "__%s__srcs"),
                        /* declared classpath */ ImmutableSortedSet.of(),
                        DEFAULT_JAVAC,
                        JavacOptions.builder(ANDROID_JAVAC_OPTIONS)
                            .setAnnotationProcessingParams(AnnotationProcessingParams.EMPTY)
                            .build(),
                        null,
                        pathResolver,
                        new FakeProjectFilesystem(),
                        new ClasspathChecker(),
                        /* directToJarOutputSettings */ Optional.empty())
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
        ImmutableSet.of(Paths.get(rDotJavaBinFolder), Paths.get(rDotJavaOutputJar)),
        buildableContext.getRecordedArtifacts());
  }

  @Test
  public void testRDotJavaBinFolder() {
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    DummyRDotJava dummyRDotJava =
        new DummyRDotJava(
            new FakeBuildRuleParamsBuilder(
                    BuildTargetFactory.newInstance("//java/com/example:library"))
                .build(),
            ruleFinder,
            ImmutableSet.of(),
            new JavacToJarStepFactory(
                DEFAULT_JAVAC, ANDROID_JAVAC_OPTIONS, JavacOptionsAmender.IDENTITY),
            /* forceFinalResourceIds */ false,
            Optional.empty(),
            Optional.empty(),
            false);
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
