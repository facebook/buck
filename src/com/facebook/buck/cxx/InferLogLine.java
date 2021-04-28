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

package com.facebook.buck.cxx;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import org.immutables.value.Value;

/** Represents infer log line */
@BuckStyleValue
public abstract class InferLogLine {

  private static final String SPLIT_TOKEN = "\t";

  abstract BuildTarget getBuildTarget();

  abstract AbsPath getOutputPath();

  /** Returns formatted infer log line */
  @Value.Derived
  public String getFormattedString() {
    BuildTarget buildTarget = getBuildTarget();
    return String.join(
        SPLIT_TOKEN,
        buildTarget.toString(),
        buildTarget.getFlavors().getSet().toString(),
        getOutputPath().toString());
  }

  public static InferLogLine of(BuildTarget buildTarget, AbsPath outputPath) {
    return ImmutableInferLogLine.ofImpl(buildTarget, outputPath);
  }
}
