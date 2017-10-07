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

package com.facebook.buck.js;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.hamcrest.Matchers.any;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.apple.AppleBundleResources;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import org.easymock.EasyMock;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class JsBundleGenruleDescriptionTest {
  private static final BuildTarget genruleTarget =
      BuildTargetFactory.newInstance("//:bundle-genrule");
  private static final BuildTarget defaultBundleTarget =
      BuildTargetFactory.newInstance("//js:bundle");
  private TestSetup setup;

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() {
    setUp(new Flavor[0]);
  }

  private void setUp(Flavor... extraFlavors) {
    setUp(defaultBundleTarget, extraFlavors);
  }

  private void setUp(BuildTarget bundleTarget, Flavor... extraFlavors) {
    JsTestScenario scenario =
        JsTestScenario.builder()
            .bundleWithDeps(bundleTarget)
            .bundleGenrule(genruleTarget, bundleTarget)
            .build();

    setup = new TestSetup(scenario, genruleTarget.withAppendedFlavors(extraFlavors), bundleTarget);
  }

  @Test
  public void dependsOnSpecifiedJsBundle() {
    assertEquals(ImmutableSortedSet.of(setup.jsBundle()), setup.genrule().getBuildDeps());
  }

  @Test
  public void forwardsFlavorsToJsBundle() {
    Flavor[] extraFlavors = {JsFlavors.IOS, JsFlavors.RELEASE};
    setUp(defaultBundleTarget.withAppendedFlavors(JsFlavors.RAM_BUNDLE_INDEXED), extraFlavors);
    assertEquals(
        ImmutableSortedSet.of(setup.jsBundle(extraFlavors)), setup.genrule().getBuildDeps());
  }

  @Test
  public void failsForNonJsBundleTargets() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        Matchers.equalTo(
            "The 'js_bundle' argument of //:bundle-genrule, //js:bundle, must correspond to a js_bundle() rule."));
    JsTestScenario scenario = JsTestScenario.builder().arbitraryRule(defaultBundleTarget).build();

    new JsBundleGenruleBuilder(genruleTarget, defaultBundleTarget, scenario.filesystem)
        .build(scenario.resolver, scenario.filesystem);
  }

  @Test
  public void underlyingJsBundleIsARuntimeDep() {
    assertArrayEquals(
        new BuildTarget[] {defaultBundleTarget},
        setup.genrule().getRuntimeDeps(new SourcePathRuleFinder(setup.resolver())).toArray());
  }

  @Test
  public void hasSameBundleNameAsJsBundle() {
    assertEquals(setup.jsBundle().getBundleName(), setup.genrule().getBundleName());
  }

  @Test
  public void addsBundleAndBundleNameAsEnvironmentVariable() {
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(setup.resolver()));
    ExecutionContext context = TestExecutionContext.newBuilder().build();

    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    setup.genrule().addEnvironmentVariables(pathResolver, context, builder);
    ImmutableMap<String, String> env = builder.build();

    assertThat(
        env,
        hasEntry(
            "JS_DIR",
            pathResolver.getAbsolutePath(setup.jsBundle().getSourcePathToOutput()).toString()));
    assertThat(env, hasEntry("JS_BUNDLE_NAME", setup.jsBundle().getBundleName()));
  }

  @Test
  public void exportsResourcesOfJsBundle() {
    assertEquals(
        setup.jsBundle().getSourcePathToResources(), setup.genrule().getSourcePathToResources());
  }

  @Test
  public void delegatesAndroidPackageableBehaviorToBundle() {
    setUp(defaultBundleTarget.withAppendedFlavors(JsFlavors.ANDROID));

    JsBundleAndroid jsBundleAndroid = setup.jsBundleAndroid();
    assertEquals(
        jsBundleAndroid.getRequiredPackageables(), setup.genrule().getRequiredPackageables());

    AndroidPackageableCollector collector = EasyMock.createMock(AndroidPackageableCollector.class);
    expect(collector.addAssetsDirectory(anyObject(BuildTarget.class), anyObject(SourcePath.class)))
        .andReturn(collector);
    replay(collector);
    setup.genrule().addToCollector(collector);
    verify(collector);
  }

  @Test
  public void doesNothingIfUnderlyingBundleIsNotForAndroid() {
    assertEquals(ImmutableList.of(), setup.genrule().getRequiredPackageables());

    AndroidPackageableCollector collector = EasyMock.createMock(AndroidPackageableCollector.class);
    replay(collector);
    setup.genrule().addToCollector(collector);
  }

  @Test
  public void addAppleBundleResourcesIsDelegatedToUnderlyingBundle() {
    AppleBundleResources.Builder genruleBuilder = AppleBundleResources.builder();
    new JsBundleGenruleDescription()
        .addAppleBundleResources(
            genruleBuilder,
            setup.targetNode(),
            setup.rule().getProjectFilesystem(),
            setup.resolver());

    AppleBundleResources expected =
        AppleBundleResources.builder()
            .addDirsContainingResourceDirs(
                setup.genrule().getSourcePathToOutput(),
                setup.jsBundle().getSourcePathToResources())
            .build();
    assertEquals(expected, genruleBuilder.build());
  }

  @Test
  public void exportsSourceMapOfJsBundle() {
    assertEquals(
        setup.jsBundle().getSourcePathToSourceMap(), setup.genrule().getSourcePathToSourceMap());
  }

  @Test
  public void exposesSourceMapOfJsBundleWithSpecialFlavor() {
    setUp(JsFlavors.SOURCE_MAP);

    assertEquals(setup.jsBundle().getSourcePathToSourceMap(), setup.rule().getSourcePathToOutput());
  }

  @Test
  public void exposeDepsFileOfJsBundleWithSpecialFlavor() {
    setUp(JsFlavors.DEPENDENCY_FILE);

    assertEquals(
        setup.jsBundleDepsFile().getSourcePathToOutput(), setup.rule().getSourcePathToOutput());
  }

  @Test
  public void createsJsDir() {
    JsBundleGenrule genrule = setup.genrule();
    BuildContext context =
        FakeBuildContext.withSourcePathResolver(
            DefaultSourcePathResolver.from(new SourcePathRuleFinder(setup.resolver())));
    FakeBuildableContext buildableContext = new FakeBuildableContext();
    ImmutableList<Step> buildSteps =
        ImmutableList.copyOf(genrule.getBuildSteps(context, buildableContext));

    MkdirStep expectedStep =
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                genrule.getProjectFilesystem(),
                context.getSourcePathResolver().getRelativePath(genrule.getSourcePathToOutput())));
    assertThat(buildSteps, hasItem(expectedStep));

    int mkJsDirIdx = buildSteps.indexOf(expectedStep);
    assertThat(buildSteps.subList(mkJsDirIdx, buildSteps.size()), not(hasItem(any(RmStep.class))));
  }

  private static class TestSetup {
    private final BuildTarget target;
    private final JsTestScenario scenario;
    private final BuildTarget bundleTarget;

    TestSetup(JsTestScenario scenario, BuildTarget target, BuildTarget bundleTarget) {
      this.target = target;
      this.scenario = scenario;
      this.bundleTarget = bundleTarget;
    }

    BuildRule rule() {
      return scenario.resolver.requireRule(target);
    }

    JsBundleGenrule genrule() {
      return (JsBundleGenrule) rule();
    }

    @SuppressWarnings("unchecked")
    TargetNode<JsBundleGenruleDescriptionArg, ?> targetNode() {
      TargetNode<?, ?> targetNode = scenario.targetGraph.get(target);
      return (TargetNode<JsBundleGenruleDescriptionArg, ?>) targetNode;
    }

    JsBundleOutputs jsBundle(Flavor... extraFlavors) {
      return (JsBundleOutputs)
          resolver().requireRule(bundleTarget.withAppendedFlavors(extraFlavors));
    }

    JsBundleAndroid jsBundleAndroid() {
      return resolver().getRuleWithType(bundleTarget, JsBundleAndroid.class);
    }

    BuildRule jsBundleDepsFile() {
      return resolver().requireRule(bundleTarget.withAppendedFlavors(JsFlavors.DEPENDENCY_FILE));
    }

    BuildRuleResolver resolver() {
      return scenario.resolver;
    }
  }
}
