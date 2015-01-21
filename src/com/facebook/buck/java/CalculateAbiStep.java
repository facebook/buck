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

package com.facebook.buck.java;

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.abi.StubJar;
import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ImmutableSha1HashCode;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;

import java.io.IOException;
import java.nio.file.Path;

public class CalculateAbiStep implements Step {

  private final BuildableContext buildableContext;
  private final Path binaryJar;
  private final Path abiJar;

  public CalculateAbiStep(
      BuildableContext buildableContext,
      Path binaryJar,
      Path abiJar) {
    this.buildableContext = buildableContext;
    this.binaryJar = binaryJar;
    this.abiJar = abiJar;
  }

  @Override
  public int execute(ExecutionContext context) {
    String fileSha1;
    try {
      Path out = getPathToHash(context, buildableContext);

      fileSha1 = context.getProjectFilesystem().computeSha1(out);
    } catch (IOException e) {
      context.logError(e, "Failed to calculate ABI for %s.", binaryJar);
      return 1;
    }

    Sha1HashCode abiKey = ImmutableSha1HashCode.of(fileSha1);
    buildableContext.addMetadata(AbiRule.ABI_KEY_ON_DISK_METADATA, abiKey.getHash());

    return 0;
  }

  private Path getPathToHash(
      ExecutionContext context,
      BuildableContext buildableContext) throws IOException {
    ProjectFilesystem filesystem = context.getProjectFilesystem();
    Path binJar = filesystem.resolve(binaryJar);

    try {
      new StubJar(binJar).writeTo(filesystem, abiJar);
      buildableContext.recordArtifact(abiJar);
      return abiJar;
    } catch (IllegalArgumentException e) {
      // Thrown when ASM chokes on an input file. Fall back to the input jar, but warn the user.
      context.postEvent(
          ConsoleEvent.warning(
              "Unable to create abi jar from %s. Falling back to hashing that jar",
              binaryJar));
      return binJar;
    }
  }

  @Override
  public String getShortName() {
    return "calculate_abi";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("%s %s", getShortName(), binaryJar);
  }
}
