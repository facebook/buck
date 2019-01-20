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

package com.facebook.buck.unarchive;

import static com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem.createJavaOnlyFilesystem;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.util.unarchive.ArchiveFormat;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class UntarStepTest {

  @Test
  public void testGetShortName() {
    Path tarFile = Paths.get("the/tarchive.tar");
    Path outputDirectory = Paths.get("an/output/dir");
    UntarStep untarStep =
        new UntarStep(
            createJavaOnlyFilesystem(),
            tarFile,
            outputDirectory,
            Optional.empty(),
            ArchiveFormat.TAR);
    assertEquals("untar", untarStep.getShortName());
  }

  @Test
  public void testGetShellCommand() {
    ProjectFilesystem projectFilesystem =
        new FakeProjectFilesystem() {
          @Override
          public Path resolve(Path relativePath) {
            return Paths.get("/abs/path").resolve(relativePath);
          }
        };

    Path tarFile = Paths.get("the/tarchive.tar");
    Path outputDirectory = Paths.get("an/output/dir");
    UntarStep untarStep =
        new UntarStep(
            projectFilesystem, tarFile, outputDirectory, Optional.empty(), ArchiveFormat.TAR);

    ExecutionContext executionContext = TestExecutionContext.newInstance();
    assertEquals(
        "tar xf /abs/path/the/tarchive.tar -C /abs/path/an/output/dir",
        untarStep.getDescription(executionContext));
  }

  @Test
  public void validateTarDoesNotThrow() {
    validateTarTypesDoNotThrow(ArchiveFormat.TAR);
  }

  @Test
  public void validateTarBz2DoesNotThrow() {
    validateTarTypesDoNotThrow(ArchiveFormat.TAR_BZ2);
  }

  @Test
  public void validateTarGzDoesNotThrow() {
    validateTarTypesDoNotThrow(ArchiveFormat.TAR_GZ);
  }

  @Test
  public void validateTarXzDoesNotThrow() {
    validateTarTypesDoNotThrow(ArchiveFormat.TAR_XZ);
  }

  @Test
  public void validateZipThrows() {
    validateNonTarTypesThrow(ArchiveFormat.ZIP);
  }

  @Test
  public void validateMiscNonTarTypesThrow() {
    for (ArchiveFormat format : ArchiveFormat.values()) {
      switch (format) {
        case TAR:
        case TAR_BZ2:
        case TAR_GZ:
        case TAR_XZ:
        case TAR_ZSTD:
        case ZIP:
          continue;
        default:
          validateNonTarTypesThrow(format);
      }
    }
  }

  private void validateTarTypesDoNotThrow(ArchiveFormat format) {
    Path tarFile = Paths.get("the/tarchive.tar");
    Path outputDirectory = Paths.get("an/output/dir");

    new UntarStep(createJavaOnlyFilesystem(), tarFile, outputDirectory, Optional.empty(), format);
  }

  private void validateNonTarTypesThrow(ArchiveFormat format) {
    Path tarFile = Paths.get("the/tarchive.tar");
    Path outputDirectory = Paths.get("an/output/dir");

    try {
      new UntarStep(createJavaOnlyFilesystem(), tarFile, outputDirectory, Optional.empty(), format);
      Assert.fail(String.format("Format %s should throw an exception, and it didn't", format));
    } catch (AssertionError e) {
      throw e;
    } catch (RuntimeException e) {
      Assert.assertThat(
          e.getMessage(), Matchers.containsString("Invalid archive format given to untar step"));
    }
  }
}
