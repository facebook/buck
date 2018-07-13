/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.features.project.intellij;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.features.project.intellij.model.ModuleIndexEntry;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;

public class IntellijModulesListParserTest {

  @Rule public TemporaryPaths temporaryPaths = new TemporaryPaths();

  @Test(expected = HumanReadableException.class)
  public void throwsGoodExceptionForMalformedXmlUnterminatedTag() throws Exception {
    validateModulesList("<this><is bad=\"xml\"", ImmutableSet.of());
  }

  @Test(expected = HumanReadableException.class)
  public void throwsGoodExceptionForMalformedXmlUnclosedTag() throws Exception {
    validateModulesList("<this><is bad=\"xml\" />", ImmutableSet.of());
  }

  @Test(expected = HumanReadableException.class)
  public void throwsGoodExceptionForMalformedXmlUnterminatedAttribute() throws Exception {
    validateModulesList("<this><is bad=\"", ImmutableSet.of());
  }

  @Test
  public void doesNotCrashWithNoProjectTag() throws Exception {
    validateModulesList(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + "<noProjectTag />", ImmutableSet.of());
  }

  @Test
  public void doesNotCrashWithNoComponentTag() throws Exception {
    validateModulesList(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<project>"
            + "  <noComponentTag />"
            + "</project>",
        ImmutableSet.of());
  }

  @Test
  public void doesNotCrashWithNoModulesTag() throws Exception {
    validateModulesList(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<project>"
            + "  <component>"
            + "    <noModulesTag />"
            + "  </component>"
            + "</project>",
        ImmutableSet.of());
  }

  @Test
  public void doesNotCrashWithNoModuleTag() throws Exception {
    validateModulesList(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<project>"
            + "  <component>"
            + "    <modules>"
            + "      <!-- Empty Modules Tag (no <module>) -->"
            + "    </modules>"
            + "  </component>"
            + "</project>",
        ImmutableSet.of());
  }

  @Test
  public void doesNotCrashWithNoFilePathAttribute() throws Exception {
    validateModulesList(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<project>"
            + "  <component>"
            + "    <modules>"
            + "      <module missing-attribute=\"filepath\" group=\"Group\" />"
            + "    </modules>"
            + "  </component>"
            + "</project>",
        ImmutableSet.of());
  }

  @Test
  public void doesNotCrashWithNoGroupAttribute() throws Exception {
    validateModulesList(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<project>"
            + "  <component>"
            + "    <modules>"
            + "      <module missing-attribute=\"group\" filepath=\"$PROJECT_DIR$/mod.iml\" />"
            + "    </modules>"
            + "  </component>"
            + "</project>",
        ImmutableSet.of("mod.iml"));
  }

  @Test
  public void happyPath() throws Exception {
    validateModulesList(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<project>"
            + "  <component>"
            + "    <modules>"
            + "      <module filepath=\"$PROJECT_DIR$/mod.iml\" group=\"Group\" />"
            + "    </modules>"
            + "  </component>"
            + "</project>",
        ImmutableSet.of("mod.iml"));
  }

  private void validateModulesList(String xml, Set<String> expectedModuleFilePaths)
      throws IOException {
    final Path modulesPath = temporaryPaths.newFile();
    Files.write(modulesPath, xml.getBytes(Charset.forName("UTF-8")));
    IntellijModulesListParser parser = new IntellijModulesListParser();
    final ImmutableSortedSet<String> parsedModulePaths =
        parser
            .getAllModules(modulesPath)
            .stream()
            .map(ModuleIndexEntry::getFilePath)
            .map(Path::toString)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    assertEquals(ImmutableSortedSet.copyOf(expectedModuleFilePaths), parsedModulePaths);
  }
}
