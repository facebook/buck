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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.shell.WorkerShellStep;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Before;
import org.junit.Test;

public class JsBundleWorkerJobArgsTest {
  private static final Pattern OUT_FILE_PATTERN = Pattern.compile("--out\\s+(\\S+)");
  private JsTestScenario scenario;
  private BuildContext context;
  private FakeBuildableContext fakeBuildableContext;

  @Before
  public void setUp() throws NoSuchBuildTargetException {
    scenario = JsTestScenario.builder().build();
    context =
        FakeBuildContext.withSourcePathResolver(
            DefaultSourcePathResolver.from(new SourcePathRuleFinder(scenario.resolver)));
    fakeBuildableContext = new FakeBuildableContext();
  }

  @Test
  public void testFileRamBundleFlavor() throws NoSuchBuildTargetException {
    JsBundle bundle =
        scenario.createBundle(
            targetWithFlavors("//:arbitrary", JsFlavors.RAM_BUNDLE_FILES), ImmutableSortedSet.of());

    assertThat(getJobArgs(bundle), startsWith("bundle --files-rambundle "));
  }

  @Test
  public void testIndexedRamBundleFlavor() throws NoSuchBuildTargetException {
    JsBundle bundle =
        scenario.createBundle(
            targetWithFlavors("//:arbitrary", JsFlavors.RAM_BUNDLE_INDEXED),
            ImmutableSortedSet.of());

    assertThat(getJobArgs(bundle), startsWith("bundle --indexed-rambundle "));
  }

  @Test(expected = FlavorDomainException.class)
  public void testMultipleRamBundleFlavorsFail() throws NoSuchBuildTargetException {
    JsBundle bundle =
        scenario.createBundle(
            targetWithFlavors(
                "//:arbitrary", JsFlavors.RAM_BUNDLE_FILES, JsFlavors.RAM_BUNDLE_INDEXED),
            ImmutableSortedSet.of());

    getJobArgs(bundle);
  }

  @Test
  public void testBuildRootIsPassed() throws NoSuchBuildTargetException {
    JsBundle bundle = scenario.createBundle("//:arbitrary", ImmutableSortedSet.of());
    assertThat(
        getJobArgs(bundle),
        containsString(String.format(" --root %s ", scenario.filesystem.getRootPath())));
  }

  @Test
  public void testBundleNameCanBeSet() throws NoSuchBuildTargetException {
    String bundleName = "a-bundle-name.jsbundle";
    JsBundle bundle =
        scenario.createBundle("//:arbitrary", builder -> builder.setBundleName(bundleName));

    assertThat(getOutFile(bundle), equalTo(bundleName));
  }

  @Test
  public void testBundleNameIsFlavorSpecific() throws NoSuchBuildTargetException {
    String bundleName = "flavored.js";
    JsBundle bundle =
        scenario.createBundle(
            "//:arbitrary#the-flavor",
            builder ->
                builder.setBundleNameForFlavor(
                    ImmutableList.of(new Pair<>(InternalFlavor.of("the-flavor"), bundleName))));

    assertThat(getOutFile(bundle), equalTo(bundleName));
  }

  @Test
  public void testFirstMatchingFlavorSetsBundleName() throws NoSuchBuildTargetException {
    String bundleName = "for-ios.js";
    JsBundle bundle =
        scenario.createBundle(
            "//:arbitrary#arbitrary,release,ios",
            builder ->
                builder.setBundleNameForFlavor(
                    ImmutableList.of(
                        new Pair<>(JsFlavors.IOS, bundleName),
                        new Pair<>(JsFlavors.RELEASE, "other-name"))));

    assertThat(getOutFile(bundle), equalTo(bundleName));
  }

  private static String targetWithFlavors(String target, Flavor... flavors) {
    return BuildTargetFactory.newInstance(target)
        .withAppendedFlavors(flavors)
        .getFullyQualifiedName();
  }

  private String getJobArgs(JsBundle bundle) {
    return RichStream.from(bundle.getBuildSteps(context, fakeBuildableContext))
        .filter(WorkerShellStep.class)
        .findFirst()
        .orElseThrow(
            () ->
                new HumanReadableException("build steps don't contain a WorkerShellStep instance"))
        .getWorkerJobParamsToUse(Platform.UNKNOWN)
        .getJobArgs();
  }

  private String getOutFile(JsBundle bundle) {
    String jobArgs = getJobArgs(bundle);
    Matcher matcher = OUT_FILE_PATTERN.matcher(jobArgs);
    Preconditions.checkState(
        matcher.find(), "worker job arguments don't contain `--out ...` (got `%s`)", jobArgs);
    return Paths.get(matcher.group(1)).getFileName().toString();
  }
}
