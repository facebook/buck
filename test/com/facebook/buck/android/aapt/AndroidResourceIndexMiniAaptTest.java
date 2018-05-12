/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.android.aapt;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.android.aapt.MiniAapt.ResourceParseException;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.rules.resolver.impl.TestBuildRuleResolver;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import javax.xml.xpath.XPathExpressionException;
import org.hamcrest.core.IsEqual;
import org.hamcrest.junit.ExpectedException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidResourceIndexMiniAaptTest {
  private final SourcePathResolver resolver =
      DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestBuildRuleResolver()));

  @Rule public ExpectedException thrown = ExpectedException.none();
  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;
  private ProjectFilesystem filesystem;
  private MiniAapt aapt;

  @Before
  public void setUp() throws InterruptedException, IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    filesystem = TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("android_resources.json"),
            ImmutableSet.of(),
            false,
            false,
            MiniAapt.ResourceCollectionType.ANDROID_RESOURCE_INDEX);
  }

  @Test
  public void testFindingResourceIdsInXml()
      throws IOException, XPathExpressionException, MiniAapt.ResourceParseException {
    aapt.processXmlFile(filesystem, Paths.get("sample_resources_1.xml"), ImmutableSet.builder());

    Set<AndroidResourceIndexEntry> definitions =
        ((AndroidResourceIndexCollector) aapt.getResourceCollector()).getResourceIndex();

    assertEquals(
        ImmutableSet.of(
            AndroidResourceIndexEntry.of(
                RType.ID, "button1", 4, 41, Paths.get("sample_resources_1.xml")),
            AndroidResourceIndexEntry.of(
                RType.ID, "button3", 7, 55, Paths.get("sample_resources_1.xml"))),
        definitions);
  }

  @Test
  public void testParsingValuesFiles() throws IOException, MiniAapt.ResourceParseException {
    Path valuesFile = Paths.get("sample_resources_2.xml");
    aapt.processValuesFile(filesystem, valuesFile);

    Set<AndroidResourceIndexEntry> definitions =
        ((AndroidResourceIndexCollector) aapt.getResourceCollector()).getResourceIndex();

    assertEquals(
        ImmutableSet.of(
            AndroidResourceIndexEntry.of(RType.STRING, "hello", 2, 25, valuesFile),
            AndroidResourceIndexEntry.of(RType.PLURALS, "people", 4, 27, valuesFile),
            AndroidResourceIndexEntry.of(RType.INTEGER, "number", 9, 27, valuesFile),
            AndroidResourceIndexEntry.of(RType.DIMEN, "dimension", 10, 28, valuesFile),
            AndroidResourceIndexEntry.of(RType.STYLEABLE, "MyNiceView", 11, 41, valuesFile),
            AndroidResourceIndexEntry.of(
                RType.STYLEABLE, "MyNiceView_titleText", 11, 41, valuesFile),
            AndroidResourceIndexEntry.of(
                RType.STYLEABLE, "MyNiceView_subtitleText", 11, 41, valuesFile),
            AndroidResourceIndexEntry.of(
                RType.STYLEABLE, "MyNiceView_complexAttr", 11, 41, valuesFile),
            AndroidResourceIndexEntry.of(
                RType.STYLEABLE, "MyNiceView_android_layout_gravity", 11, 41, valuesFile),
            AndroidResourceIndexEntry.of(RType.ATTR, "titleText", 11, 41, valuesFile),
            AndroidResourceIndexEntry.of(RType.ATTR, "subtitleText", 11, 41, valuesFile),
            AndroidResourceIndexEntry.of(RType.ATTR, "complexAttr", 11, 41, valuesFile),
            AndroidResourceIndexEntry.of(RType.ID, "some_id", 23, 36, valuesFile),
            AndroidResourceIndexEntry.of(RType.STYLE, "Widget_Theme", 24, 31, valuesFile)),
        definitions);
  }

  @Test
  public void testParsingValuesExcludedFromResMap() throws IOException, ResourceParseException {
    aapt.processValuesFile(filesystem, Paths.get("sample_resources_3.xml"));

    Set<AndroidResourceIndexEntry> definitions =
        ((AndroidResourceIndexCollector) aapt.getResourceCollector()).getResourceIndex();

    assertTrue(definitions.isEmpty());
  }

  @Test
  public void testParsingValuesNotExcludedFromResIndex()
      throws IOException, ResourceParseException {
    aapt.processValuesFile(filesystem, Paths.get("sample_resources_4.xml"));

    Set<AndroidResourceIndexEntry> definitions =
        ((AndroidResourceIndexCollector) aapt.getResourceCollector()).getResourceIndex();

    assertEquals(
        definitions,
        ImmutableSet.of(
            AndroidResourceIndexEntry.of(
                RType.STRING, "hello", 3, 22, Paths.get("sample_resources_4.xml"))));
  }

  @Test
  public void testParsingAndroidDrawables() throws IOException, ResourceParseException {
    aapt.processDrawables(filesystem, Paths.get("sample_resources_android_drawables.xml"));

    Set<AndroidResourceIndexEntry> definitions =
        ((AndroidResourceIndexCollector) aapt.getResourceCollector()).getResourceIndex();

    assertThat(
        definitions,
        IsEqual.equalToObject(
            ImmutableSet.of(
                AndroidResourceIndexEntry.of(
                    RType.DRAWABLE,
                    "sample_resources_android_drawables",
                    0,
                    0,
                    Paths.get("sample_resources_android_drawables.xml")))));
  }

  @Test
  public void testParsingCustomDrawables() throws IOException, ResourceParseException {
    aapt.processDrawables(filesystem, Paths.get("sample_resources_custom_drawables.xml"));

    Set<AndroidResourceIndexEntry> definitions =
        ((AndroidResourceIndexCollector) aapt.getResourceCollector()).getResourceIndex();

    assertThat(
        definitions,
        IsEqual.equalToObject(
            ImmutableSet.of(
                AndroidResourceIndexEntry.of(
                    RType.DRAWABLE,
                    "sample_resources_custom_drawables",
                    0,
                    0,
                    Paths.get("sample_resources_custom_drawables.xml")))));
  }

  @Test
  public void testParsingGrayscaleImage() throws IOException, ResourceParseException {
    ImmutableList<String> lines = ImmutableList.<String>builder().add("").build();
    filesystem.writeLinesToPath(lines, Paths.get("fbui_tomato.png"));
    aapt.processDrawables(filesystem, Paths.get("fbui_tomato.g.png"));

    Set<AndroidResourceIndexEntry> definitions =
        ((AndroidResourceIndexCollector) aapt.getResourceCollector()).getResourceIndex();

    assertThat(
        definitions,
        IsEqual.equalToObject(
            ImmutableSet.of(
                AndroidResourceIndexEntry.of(
                    RType.DRAWABLE, "fbui_tomato", 0, 0, Paths.get("fbui_tomato.g.png")))));
  }

  @Test(expected = ResourceParseException.class)
  public void testInvalidResourceType() throws IOException, ResourceParseException {
    aapt.processValuesFile(filesystem, Paths.get("sample_resources_invalid_resource_type.xml"));
  }

  @Test(expected = ResourceParseException.class)
  public void testInvalidItemResource() throws IOException, ResourceParseException {
    aapt.processValuesFile(filesystem, Paths.get("sample_resources_invalid_definition.xml"));
  }

  @Test
  public void testInvalidDefinition() throws XPathExpressionException, IOException {
    try {
      aapt.processXmlFile(
          filesystem, Paths.get("sample_resources_invalid_definition.xml"), ImmutableSet.builder());
      fail("MiniAapt should throw parsing '@+string/button1'");
    } catch (ResourceParseException e) {
      assertThat(e.getMessage(), containsString("Invalid definition of a resource"));
    }
  }

  @Test
  public void testInvalidReference() throws IOException, XPathExpressionException {
    try {
      aapt.processXmlFile(
          filesystem, Paths.get("sample_resources_invalid_reference.xml"), ImmutableSet.builder());
      fail("MiniAapt should throw parsing '@someresource/button2'");
    } catch (ResourceParseException e) {
      assertThat(e.getMessage(), containsString("Invalid reference '@someresource/button2'"));
    }
  }

  @Test
  public void testMissingNameAttribute() throws IOException, ResourceParseException {
    thrown.expect(ResourceParseException.class);
    thrown.expectMessage("Error: expected a 'name' attribute in node 'string' with value 'Howdy!'");

    aapt.processValuesFile(filesystem, Paths.get("sample_resources_missing_name_attr.xml"));
  }

  @Test
  public void testInvalidNodeId()
      throws IOException, XPathExpressionException, ResourceParseException {
    thrown.expect(ResourceParseException.class);
    thrown.expectMessage("Invalid definition of a resource: '@button2'");

    aapt.processXmlFile(
        filesystem, Paths.get("sample_resources_invalid_node_id.xml"), ImmutableSet.builder());
  }

  @Test
  public void testProcessFileNamesInDirectory() throws IOException, ResourceParseException {
    filesystem.createParentDirs("sample_res/values/value.xml~");
    filesystem.touch(Paths.get("sample_res/values/value.xml~"));
    filesystem.writeContentsToPath(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<resources>"
            + "<bool name=\"v\">false</bool>"
            + "</resources>",
        Paths.get("sample_res/values/value.xml~"));
    aapt.processFileNamesInDirectory(filesystem, Paths.get("sample_res/drawable"));
    aapt.processFileNamesInDirectory(filesystem, Paths.get("sample_res/drawable-ldpi"));
    aapt.processFileNamesInDirectory(filesystem, Paths.get("sample_res/transition-v19"));
    aapt.processValues(
        filesystem,
        new DefaultBuckEventBus(FakeClock.doNotCare(), new BuildId("")),
        Paths.get("sample_res/values"));

    assertEquals(
        ImmutableSet.of(
            AndroidResourceIndexEntry.of(
                RType.DRAWABLE, "icon", 0, 0, Paths.get("sample_res/drawable/icon.png")),
            AndroidResourceIndexEntry.of(
                RType.DRAWABLE,
                "nine_patch",
                0,
                0,
                Paths.get("sample_res/drawable-ldpi/nine_patch.9.png")),
            AndroidResourceIndexEntry.of(
                RType.TRANSITION,
                "some_transition",
                0,
                0,
                Paths.get("sample_res/transition-v19/some_transition.xml"))),
        ((AndroidResourceIndexCollector) aapt.getResourceCollector()).getResourceIndex());
  }
}
