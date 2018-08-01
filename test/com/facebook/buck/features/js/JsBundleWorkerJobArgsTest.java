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

package com.facebook.buck.features.js;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomainException;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.shell.WorkerShellStep;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.types.Pair;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;

public class JsBundleWorkerJobArgsTest {
  private JsTestScenario scenario;
  private BuildContext context;
  private FakeBuildableContext fakeBuildableContext;

  @Before
  public void setUp() {
    scenario = JsTestScenario.builder().build();
    fakeBuildableContext = new FakeBuildableContext();
    buildContext(scenario);
  }

  @Test
  public void testFileRamBundleFlavor() throws IOException {
    JsBundle bundle =
        scenario.createBundle(
            targetWithFlavors("//:arbitrary", JsFlavors.RAM_BUNDLE_FILES), ImmutableSortedSet.of());

    JsonNode args = ObjectMappers.readValue(getJobArgs(bundle), JsonNode.class);
    assertThat(args.get("ramBundle").asText(), equalTo("files"));

    JsonNode flavors = args.get("flavors");
    assertThat(flavors, equalTo(arrayNodeOf(JsFlavors.RAM_BUNDLE_FILES)));
  }

  @Test
  public void testIndexedRamBundleFlavor() throws IOException {
    JsBundle bundle =
        scenario.createBundle(
            targetWithFlavors("//:arbitrary", JsFlavors.RAM_BUNDLE_INDEXED),
            ImmutableSortedSet.of());

    JsonNode args = ObjectMappers.readValue(getJobArgs(bundle), JsonNode.class);
    assertThat(args.get("ramBundle").asText(), equalTo("indexed"));
    assertThat(args.get("flavors"), equalTo(arrayNodeOf(JsFlavors.RAM_BUNDLE_INDEXED)));
  }

  @Test(expected = FlavorDomainException.class)
  public void testMultipleRamBundleFlavorsFail() {
    JsBundle bundle =
        scenario.createBundle(
            targetWithFlavors(
                "//:arbitrary", JsFlavors.RAM_BUNDLE_FILES, JsFlavors.RAM_BUNDLE_INDEXED),
            ImmutableSortedSet.of());

    getJobArgs(bundle);
  }

  @Test
  public void testBuildRootIsPassed() throws IOException {
    JsBundle bundle = scenario.createBundle("//:arbitrary", ImmutableSortedSet.of());
    JsonNode args = ObjectMappers.readValue(getJobArgs(bundle), JsonNode.class);
    assertThat(
        args.get("rootPath").asText(), equalTo(scenario.filesystem.getRootPath().toString()));
  }

  @Test
  public void testBundleNameCanBeSet() {
    String bundleName = "a-bundle-name.jsbundle";
    JsBundle bundle =
        scenario.createBundle("//:arbitrary", builder -> builder.setBundleName(bundleName));

    assertThat(getOutFile(bundle), equalTo(bundleName));
  }

  @Test
  public void testBundleNameIsFlavorSpecific() {
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
  public void testFirstMatchingFlavorSetsBundleName() {
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
    String fileNameWithEscapes;
    if (Platform.detect() == Platform.WINDOWS) {
      // Double quote not allowed as Windows path character.
      // The Java nio Windows normalizer gives errors for the \t
      // and all other escape characters, but \'.
      fileNameWithEscapes = "//needs\'\':escaping";
    } else {
      fileNameWithEscapes = "//needs\t\":escaping";
    }

    BuildTarget referenced = BuildTargetFactory.newInstance(fileNameWithEscapes);
    JsTestScenario scenario = JsTestScenario.builder().arbitraryRule(referenced).build();
    JsBundle bundle =
        scenario.createBundle(
            "//:bundle",
            builder -> builder.setExtraJson("[\"1 %s 2\"]", LocationMacro.of(referenced)));

    buildContext(scenario);
    String expectedStr;

    if (Platform.detect() == Platform.WINDOWS) {
      expectedStr =
          String.format(
              "[\"1 %s\\\\buck-out\\\\gen\\\\needs\'\'\\\\escaping\\\\escaping 2\"]",
              JsUtil.escapeJsonForStringEmbedding(context.getBuildCellRootPath().toString()));
    } else {
      expectedStr =
          String.format(
              "[\"1 %s/buck-out/gen/needs\\t\\\"/escaping/escaping 2\"]",
              JsUtil.escapeJsonForStringEmbedding(context.getBuildCellRootPath().toString()));
    }

    assertThat(getJobJson(bundle).get("extraData").toString(), equalTo(expectedStr));
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
            DefaultSourcePathResolver.from(new SourcePathRuleFinder(scenario.graphBuilder)));
  }

  private ArrayNode arrayNodeOf(Object... strings) {
    return new ArrayNode(
        JsonNodeFactory.instance,
        Arrays.stream(strings)
            .map(Object::toString)
            .map(JsonNodeFactory.instance::textNode)
            .collect(ImmutableList.toImmutableList()));
  }
}
