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

import com.facebook.buck.java.abi2.StubJar;
import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;

import java.io.IOException;
import java.nio.file.Path;

class CalculateAbiStep implements Step {

  private final BuildableContext buildableContext;
  private final Path binaryJar;
  private final Path abiJar;

  CalculateAbiStep(
      BuildableContext buildableContext,
      Path binaryJar,
      Path abiJar) {
    this.buildableContext = buildableContext;
    this.binaryJar = binaryJar;
    this.abiJar = abiJar;
  }

  @Override
  public int execute(ExecutionContext context) {
    // TODO(simons): Because binaryJar could be a generated file, it may not be bit-for-bit
    // identical when generated across machines. Therefore, we should calculate its ABI based on
    // the contents of its .class files rather than just hashing its contents.
    String fileSha1;
    try {
      Path binJar = context.getProjectFilesystem().resolve(binaryJar);
      Path out = context.getProjectFilesystem().resolve(abiJar);

      new StubJar(binJar).writeTo(out);

      fileSha1 = context.getProjectFilesystem().computeSha1(out);
      buildableContext.recordArtifact(out);
    } catch (IOException e) {
      context.logError(e, "Failed to calculate ABI for %s.", binaryJar);
      return 1;
    }

    Sha1HashCode abiKey = new Sha1HashCode(fileSha1);
    buildableContext.addMetadata(AbiRule.ABI_KEY_ON_DISK_METADATA, abiKey.getHash());

    return 0;
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
