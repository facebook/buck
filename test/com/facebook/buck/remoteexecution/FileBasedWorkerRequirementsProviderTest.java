/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.remoteexecution;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.remoteexecution.proto.WorkerRequirements;
import com.facebook.buck.remoteexecution.proto.WorkerRequirements.WorkerSize;
import com.google.common.base.Charsets;
import com.google.common.io.FileWriteMode;
import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Objects;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class FileBasedWorkerRequirementsProviderTest {
  private static final String FILENAME = "file.txt";
  private static final String SHORT_NAME = "name";
  private static final String BASE_DIR = "fullName";
  private static final String FULL_NAME = "//" + BASE_DIR + ":" + SHORT_NAME;
  private static final String OTHER_SHORT_NAME = "other_rule";

  private static final String ACTION_TAGS =
      "\"{\\\"ruleName\\\": \\\"name\\\", \\\"auxiliaryBuildTag\\\": \\\"tag::tag2\\\"}\"";
  private static final String AUXILIARY_BUILD_TAG = "tag::tag2";
  private static final String ACTION_TAGS_NO_AUX_TAG =
      "\"{\\\"ruleName\\\": \\\"name\\\", \\\"auxiliaryBuildTag\\\": \\\"\\\"}\"";

  private static final String OTHER_RULE_ACTION_TAGS =
      "\"{\\\"ruleName\\\": \\\"other_rule\\\", \\\"auxiliaryBuildTag\\\": \\\"\\\"}\"";
  private static final String NO_AUXILIARY_BUILD_TAG = "";

  private static final String SIZE_SMALL = "{\"workerSize\": \"SMALL\"}";
  private static final String SIZE_LARGE = "{\"workerSize\": \"LARGE\"}";
  private static final String SIZE_UNKNOWN = "{\"workerSize\": \"UNKNOWN\"}";

  private ProjectFilesystem projectFilesystem;
  private BuildTarget buildTarget;
  private File tmp;

  @Before
  public void setUp() {
    projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    tmp = projectFilesystem.resolve(BASE_DIR).toFile();
    Assert.assertTrue(tmp.mkdir());

    buildTarget = EasyMock.createMock(BuildTarget.class);

    EasyMock.expect(buildTarget.getFullyQualifiedName()).andReturn(FULL_NAME).anyTimes();
    EasyMock.expect(buildTarget.getShortNameAndFlavorPostfix()).andReturn(SHORT_NAME).anyTimes();
    EasyMock.expect(buildTarget.getCellRelativeBasePath())
        .andReturn(
            CellRelativePath.of(CanonicalCellName.rootCell(), ForwardRelativePath.of(BASE_DIR)))
        .anyTimes();

    EasyMock.replay(buildTarget);
  }

  @Test
  public void testNoFileExistShouldReturnDefault() {
    WorkerRequirementsProvider provider =
        new FileBasedWorkerRequirementsProvider(projectFilesystem, FILENAME, true, 1000);
    WorkerRequirements workerRequirements =
        provider.resolveRequirements(buildTarget, NO_AUXILIARY_BUILD_TAG);
    Assert.assertNotNull(workerRequirements);
    Assert.assertEquals(
        FileBasedWorkerRequirementsProvider.RETRY_ON_OOM_DEFAULT, workerRequirements);

    provider = new FileBasedWorkerRequirementsProvider(projectFilesystem, FILENAME, false, 1000);
    workerRequirements = provider.resolveRequirements(buildTarget, NO_AUXILIARY_BUILD_TAG);
    Assert.assertNotNull(workerRequirements);
    Assert.assertEquals(
        FileBasedWorkerRequirementsProvider.DONT_RETRY_ON_OOM_DEFAULT, workerRequirements);
  }

  @Test
  public void testEmptyFileShouldReturnDefault() throws IOException {
    File file = Paths.get(tmp.getPath(), FILENAME).toFile();
    Assert.assertTrue(file.createNewFile());

    WorkerRequirementsProvider provider =
        new FileBasedWorkerRequirementsProvider(projectFilesystem, FILENAME, true, 1000);
    WorkerRequirements workerRequirements =
        provider.resolveRequirements(buildTarget, NO_AUXILIARY_BUILD_TAG);
    Assert.assertNotNull(workerRequirements);
    Assert.assertEquals(
        FileBasedWorkerRequirementsProvider.RETRY_ON_OOM_DEFAULT, workerRequirements);
  }

  @Test
  public void testInvalidJSONShouldReturnDefault() throws IOException {
    File file = Paths.get(tmp.getPath(), FILENAME).toFile();
    Assert.assertTrue(file.createNewFile());
    Files.asCharSink(file, Charsets.UTF_8, FileWriteMode.APPEND).write("invalid json");

    WorkerRequirementsProvider provider =
        new FileBasedWorkerRequirementsProvider(projectFilesystem, FILENAME, true, 1000);
    WorkerRequirements workerRequirements =
        provider.resolveRequirements(buildTarget, NO_AUXILIARY_BUILD_TAG);
    Assert.assertNotNull(workerRequirements);
    Assert.assertEquals(
        FileBasedWorkerRequirementsProvider.RETRY_ON_OOM_DEFAULT, workerRequirements);
  }

  @Test
  public void testRuleNotFoundShouldReturnDefault() throws IOException {
    File file = Paths.get(tmp.getPath(), FILENAME).toFile();
    Assert.assertTrue(file.createNewFile());
    Files.asCharSink(file, Charsets.UTF_8, FileWriteMode.APPEND)
        .write(String.format("{%s: %s}", OTHER_RULE_ACTION_TAGS, SIZE_SMALL));

    WorkerRequirementsProvider provider =
        new FileBasedWorkerRequirementsProvider(projectFilesystem, FILENAME, true, 1000);
    WorkerRequirements workerRequirements =
        provider.resolveRequirements(buildTarget, NO_AUXILIARY_BUILD_TAG);
    Assert.assertNotNull(workerRequirements);
    Assert.assertEquals(
        FileBasedWorkerRequirementsProvider.RETRY_ON_OOM_DEFAULT, workerRequirements);
  }

  @Test
  public void testRuleHasCustomRequirements() throws IOException {
    File file = Paths.get(tmp.getPath(), FILENAME).toFile();
    Assert.assertTrue(file.createNewFile());
    Files.asCharSink(file, Charsets.UTF_8, FileWriteMode.APPEND)
        .write(
            String.format(
                "{%s: %s, %s: %s}", OTHER_RULE_ACTION_TAGS, SIZE_SMALL, ACTION_TAGS, SIZE_LARGE));

    WorkerRequirementsProvider provider =
        new FileBasedWorkerRequirementsProvider(projectFilesystem, FILENAME, true, 1000);
    WorkerRequirements workerRequirements =
        provider.resolveRequirements(buildTarget, AUXILIARY_BUILD_TAG);
    Assert.assertNotNull(workerRequirements);
    Assert.assertEquals(WorkerSize.LARGE, workerRequirements.getWorkerSize());
  }

  @Test
  public void testRuleHasCustomRequirementsButOnlyWithoutAuxiliaryTag() throws IOException {
    File file = Paths.get(tmp.getPath(), FILENAME).toFile();
    Assert.assertTrue(file.createNewFile());
    Files.asCharSink(file, Charsets.UTF_8, FileWriteMode.APPEND)
        .write(
            String.format(
                "{%s: %s, %s: %s}",
                OTHER_RULE_ACTION_TAGS, SIZE_SMALL, ACTION_TAGS_NO_AUX_TAG, SIZE_LARGE));

    WorkerRequirementsProvider provider =
        new FileBasedWorkerRequirementsProvider(projectFilesystem, FILENAME, true, 1000);
    WorkerRequirements workerRequirements =
        provider.resolveRequirements(buildTarget, AUXILIARY_BUILD_TAG);
    Assert.assertNotNull(workerRequirements);
    Assert.assertEquals(WorkerSize.LARGE, workerRequirements.getWorkerSize());
  }

  @Test
  public void testRuleHasInvalidTypeShouldProvideDefault() throws IOException {
    File file = Paths.get(tmp.getPath(), FILENAME).toFile();
    Assert.assertTrue(file.createNewFile());
    Files.asCharSink(file, Charsets.UTF_8, FileWriteMode.APPEND)
        .write(
            String.format(
                "{%s: %s, %s: %s}", OTHER_RULE_ACTION_TAGS, SIZE_SMALL, ACTION_TAGS, SIZE_UNKNOWN));

    WorkerRequirementsProvider provider =
        new FileBasedWorkerRequirementsProvider(projectFilesystem, FILENAME, true, 1000);
    WorkerRequirements workerRequirements =
        provider.resolveRequirements(buildTarget, NO_AUXILIARY_BUILD_TAG);
    Assert.assertNotNull(workerRequirements);
    Assert.assertEquals(
        FileBasedWorkerRequirementsProvider.RETRY_ON_OOM_DEFAULT, workerRequirements);
  }

  // TODO(msienkiewicz): Remove theses tests once old format is unused.
  @Test
  public void testRuleHasCustomRequirementsInOldFormat() throws IOException {
    File file = Paths.get(tmp.getPath(), FILENAME).toFile();
    Assert.assertTrue(file.createNewFile());
    Files.asCharSink(file, Charsets.UTF_8, FileWriteMode.APPEND)
        .write(
            String.format(
                "{\"%s\": %s, \"%s\": %s}", OTHER_SHORT_NAME, SIZE_SMALL, SHORT_NAME, SIZE_LARGE));

    WorkerRequirementsProvider provider =
        new FileBasedWorkerRequirementsProvider(projectFilesystem, FILENAME, true, 1000);
    WorkerRequirements workerRequirements =
        provider.resolveRequirements(buildTarget, NO_AUXILIARY_BUILD_TAG);
    Assert.assertNotNull(workerRequirements);
    Assert.assertEquals(WorkerSize.LARGE, workerRequirements.getWorkerSize());
  }

  @Test
  public void testRuleHasInvalidTypeShouldProvideDefaultInOldFormat() throws IOException {
    File file = Paths.get(tmp.getPath(), FILENAME).toFile();
    Assert.assertTrue(file.createNewFile());
    Files.asCharSink(file, Charsets.UTF_8, FileWriteMode.APPEND)
        .write(
            String.format(
                "{\"%s\": %s, \"%s\": %s}",
                OTHER_SHORT_NAME, SIZE_SMALL, SHORT_NAME, SIZE_UNKNOWN));

    WorkerRequirementsProvider provider =
        new FileBasedWorkerRequirementsProvider(projectFilesystem, FILENAME, true, 1000);
    WorkerRequirements workerRequirements =
        provider.resolveRequirements(buildTarget, NO_AUXILIARY_BUILD_TAG);
    Assert.assertNotNull(workerRequirements);
    Assert.assertEquals(
        FileBasedWorkerRequirementsProvider.RETRY_ON_OOM_DEFAULT, workerRequirements);
  }

  @After
  public void tearDown() {
    if (tmp != null) {
      delete(tmp);
    }
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  void delete(File f) {
    if (!f.isDirectory()) {
      return;
    }

    Arrays.stream(Objects.requireNonNull(f.listFiles())).forEach(this::delete);
    f.delete();
  }
}
