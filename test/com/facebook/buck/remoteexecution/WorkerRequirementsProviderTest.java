/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.remoteexecution;

import com.facebook.buck.core.model.BuildTarget;
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

public class WorkerRequirementsProviderTest {
  private static final String FILENAME = "file.txt";
  private static final String SHORT_NAME = "name";
  private static final String FULL_NAME = "//fullName:" + SHORT_NAME;

  private BuildTarget buildTarget;
  private File tmp;

  @Before
  public void setUp() {
    tmp = Files.createTempDir();

    buildTarget = EasyMock.createMock(BuildTarget.class);

    EasyMock.expect(buildTarget.getFullyQualifiedName()).andReturn(FULL_NAME).anyTimes();
    EasyMock.expect(buildTarget.getShortName()).andReturn(SHORT_NAME).anyTimes();
    EasyMock.expect(buildTarget.getBasePath()).andReturn(tmp.toPath()).anyTimes();

    EasyMock.replay(buildTarget);
  }

  @Test
  public void testNoFileExistShouldReturnDefault() {
    WorkerRequirementsProvider provider = new WorkerRequirementsProvider(FILENAME, true, 1000);
    WorkerRequirements workerRequirements = provider.resolveRequirements(buildTarget);
    Assert.assertNotNull(workerRequirements);
    Assert.assertEquals(WorkerRequirementsProvider.DEFAULT, workerRequirements);
  }

  @Test
  public void testEmptyFileShouldReturnDefault() throws IOException {
    File file = Paths.get(tmp.getPath(), FILENAME).toFile();
    Assert.assertTrue(file.createNewFile());

    WorkerRequirementsProvider provider = new WorkerRequirementsProvider(FILENAME, true, 1000);
    WorkerRequirements workerRequirements = provider.resolveRequirements(buildTarget);
    Assert.assertNotNull(workerRequirements);
    Assert.assertEquals(WorkerRequirementsProvider.DEFAULT, workerRequirements);
  }

  @Test
  public void testInvalidJSONShouldReturnDefault() throws IOException {
    File file = Paths.get(tmp.getPath(), FILENAME).toFile();
    Assert.assertTrue(file.createNewFile());
    Files.asCharSink(file, Charsets.UTF_8, FileWriteMode.APPEND).write("invalid json");

    WorkerRequirementsProvider provider = new WorkerRequirementsProvider(FILENAME, true, 1000);
    WorkerRequirements workerRequirements = provider.resolveRequirements(buildTarget);
    Assert.assertNotNull(workerRequirements);
    Assert.assertEquals(WorkerRequirementsProvider.DEFAULT, workerRequirements);
  }

  @Test
  public void testRuleNotFoundShouldReturnDefault() throws IOException {
    File file = Paths.get(tmp.getPath(), FILENAME).toFile();
    Assert.assertTrue(file.createNewFile());
    Files.asCharSink(file, Charsets.UTF_8, FileWriteMode.APPEND)
        .write("{\"other_rule\": {\"workerSize\": \"SMALL\"}}");

    WorkerRequirementsProvider provider = new WorkerRequirementsProvider(FILENAME, true, 1000);
    WorkerRequirements workerRequirements = provider.resolveRequirements(buildTarget);
    Assert.assertNotNull(workerRequirements);
    Assert.assertEquals(WorkerRequirementsProvider.DEFAULT, workerRequirements);
  }

  @Test
  public void testRuleHasCustomRequirements() throws IOException {
    File file = Paths.get(tmp.getPath(), FILENAME).toFile();
    Assert.assertTrue(file.createNewFile());
    Files.asCharSink(file, Charsets.UTF_8, FileWriteMode.APPEND)
        .write(
            "{\"other_rule\": {\"workerSize\": \"SMALL\"}, \""
                + SHORT_NAME
                + "\": {\"workerSize\": \"LARGE\"}}");

    WorkerRequirementsProvider provider = new WorkerRequirementsProvider(FILENAME, true, 1000);
    WorkerRequirements workerRequirements = provider.resolveRequirements(buildTarget);
    Assert.assertNotNull(workerRequirements);
    Assert.assertEquals(WorkerSize.LARGE, workerRequirements.getWorkerSize());
  }

  @Test
  public void testRuleHasInvalidTypeShouldProvideDefault() throws IOException {
    File file = Paths.get(tmp.getPath(), FILENAME).toFile();
    Assert.assertTrue(file.createNewFile());
    Files.asCharSink(file, Charsets.UTF_8, FileWriteMode.APPEND)
        .write(
            "{\"other_rule\": {\"workerSize\": \"SMALL\"}, \""
                + SHORT_NAME
                + "\": {\"workerSize\": \"UNKNOWN\"}}");

    WorkerRequirementsProvider provider = new WorkerRequirementsProvider(FILENAME, true, 1000);
    WorkerRequirements workerRequirements = provider.resolveRequirements(buildTarget);
    Assert.assertNotNull(workerRequirements);
    Assert.assertEquals(WorkerRequirementsProvider.DEFAULT, workerRequirements);
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
