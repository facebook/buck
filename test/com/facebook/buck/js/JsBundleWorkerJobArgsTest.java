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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.shell.WorkerShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Collection;
import org.junit.Before;
import org.junit.Test;

public class JsBundleWorkerJobArgsTest {
  private JsTestScenario scenario;
  private BuildContext context;
  private FakeBuildableContext fakeBuildableContext;

  @Before
  public void setUp() throws NoSuchBuildTargetException {
    scenario = JsTestScenario.builder().build();
    context =
        FakeBuildContext.withSourcePathResolver(
            new SourcePathResolver(new SourcePathRuleFinder(scenario.resolver)));
    fakeBuildableContext = new FakeBuildableContext();
  }

  @Test
  public void testFileRamBundleFlavor() throws NoSuchBuildTargetException {
    final JsBundle bundle =
        scenario.createBundle(
            targetWithFlavors("//:arbitrary", JsFlavors.RAM_BUNDLE_FILES), ImmutableSortedSet.of());

    assertThat(
        getJobArgs(bundle.getBuildSteps(context, fakeBuildableContext)),
        startsWith("bundle --files-rambundle "));
  }

  @Test
  public void testIndexedRamBundleFlavor() throws NoSuchBuildTargetException {
    final JsBundle bundle =
        scenario.createBundle(
            targetWithFlavors("//:arbitrary", JsFlavors.RAM_BUNDLE_INDEXED),
            ImmutableSortedSet.of());

    assertThat(
        getJobArgs(bundle.getBuildSteps(context, fakeBuildableContext)),
        startsWith("bundle --indexed-rambundle "));
  }

  @Test(expected = FlavorDomainException.class)
  public void testMultipleRamBundleFlavorsFail() throws NoSuchBuildTargetException {
    final JsBundle bundle =
        scenario.createBundle(
            targetWithFlavors(
                "//:arbitrary", JsFlavors.RAM_BUNDLE_FILES, JsFlavors.RAM_BUNDLE_INDEXED),
            ImmutableSortedSet.of());

    getJobArgs(bundle.getBuildSteps(context, fakeBuildableContext));
  }

  @Test
  public void testBuildRootIsPassed() throws NoSuchBuildTargetException {
    final JsBundle bundle = scenario.createBundle("//:arbitrary", ImmutableSortedSet.of());
    assertThat(
        getJobArgs(bundle.getBuildSteps(context, fakeBuildableContext)),
        containsString(String.format(" --root %s ", scenario.filesystem.getRootPath())));
  }

  private static String targetWithFlavors(String target, Flavor... flavors) {
    return BuildTargetFactory.newInstance(target)
        .withAppendedFlavors(flavors)
        .getFullyQualifiedName();
  }

  private static String getJobArgs(Collection<Step> steps) {
    return RichStream.from(steps)
        .filter(WorkerShellStep.class)
        .findFirst()
        .orElseThrow(
            () ->
                new HumanReadableException("build steps don't contain a WorkerShellStep instance"))
        .getWorkerJobParamsToUse(Platform.UNKNOWN)
        .getJobArgs();
  }
}
