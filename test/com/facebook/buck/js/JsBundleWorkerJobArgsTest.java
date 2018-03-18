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

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.shell.WorkerShellStep;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.types.Pair;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Test;

public class JsBundleWorkerJobArgsTest {
  private JsTestScenario scenario;
  private BuildContext context;
  private FakeBuildableContext fakeBuildableContext;

  @Before
  public void setUp() throws NoSuchBuildTargetException {
    scenario = JsTestScenario.builder().build();
    fakeBuildableContext = new FakeBuildableContext();
    buildContext(scenario);
  }

  @Test
  public void testFileRamBundleFlavor() throws NoSuchBuildTargetException, IOException {
    JsBundle bundle =
        scenario.createBundle(
            targetWithFlavors("//:arbitrary", JsFlavors.RAM_BUNDLE_FILES), ImmutableSortedSet.of());

    JsonNode args = ObjectMappers.readValue(getJobArgs(bundle), JsonNode.class);
    assertThat(args.get("ramBundle").asText(), equalTo("files"));
  }

  @Test
  public void testIndexedRamBundleFlavor() throws NoSuchBuildTargetException, IOException {
    JsBundle bundle =
        scenario.createBundle(
            targetWithFlavors("//:arbitrary", JsFlavors.RAM_BUNDLE_INDEXED),
            ImmutableSortedSet.of());

    JsonNode args = ObjectMappers.readValue(getJobArgs(bundle), JsonNode.class);
    assertThat(args.get("ramBundle").asText(), equalTo("indexed"));
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
  public void testBuildRootIsPassed() throws NoSuchBuildTargetException, IOException {
    JsBundle bundle = scenario.createBundle("//:arbitrary", ImmutableSortedSet.of());
    JsonNode args = ObjectMappers.readValue(getJobArgs(bundle), JsonNode.class);
    assertThat(
        args.get("rootPath").asText(), equalTo(scenario.filesystem.getRootPath().toString()));
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

  @Test
  public void testLocationMacrosInExtraJsonAreExpandedAndEscaped() {
    BuildTarget referenced = BuildTargetFactory.newInstance("//needs\t\":escaping");
    JsTestScenario scenario = JsTestScenario.builder().arbitraryRule(referenced).build();
    JsBundle bundle =
        scenario.createBundle(
            "//:bundle",
            builder -> builder.setExtraJson("[\"1 %s 2\"]", LocationMacro.of(referenced)));

    buildContext(scenario);
    assertThat(
        getJobJson(bundle).get("extraData").toString(),
        equalTo(
            String.format(
                "[\"1 %s/buck-out/gen/needs\\t\\\"/escaping/escaping 2\"]",
                JsUtil.escapeJsonForStringEmbedding(context.getBuildCellRootPath().toString()))));
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

  private JsonNode getJobJson(JsBundle bundle) {
    try {
      return ObjectMappers.readValue(getJobArgs(bundle), JsonNode.class);
    } catch (IOException error) {
      throw new HumanReadableException(error, "Couldn't read bundle args as JSON");
    }
  }

  private String getOutFile(JsBundle bundle) {
    JsonNode args = getJobJson(bundle);
    return Paths.get(args.get("bundlePath").asText()).getFileName().toString();
  }

  private void buildContext(JsTestScenario scenario) {
    context =
        FakeBuildContext.withSourcePathResolver(
            DefaultSourcePathResolver.from(new SourcePathRuleFinder(scenario.resolver)));
  }
}
