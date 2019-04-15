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
package com.facebook.buck.core.rules.analysis;

import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.actions.ActionAnalysisDataLookUp;
import com.facebook.buck.core.rules.providers.ProviderInfoCollection;

/**
 * The result of performing rule analysis over a single {@link
 * com.facebook.buck.core.model.BuildTarget} rule.
 *
 * <p>This is similar to Bazel's {@code
 * com.google.devtools.build.lib.skyframe.ConfiguredTargetValue}. {@see <a
 * href="https://github.com/bazelbuild/bazel/blob/master/src/main/java/com/google/devtools/build/lib/skyframe/ConfiguredTargetValue.java">ConfiguredTargetValue</a>}
 */
public interface RuleAnalysisResult extends ActionAnalysisDataLookUp, ComputeResult {

  /** @return {@link BuildTarget} of the rule */
  BuildTarget getBuildTarget();

  /** @return a {@link ProviderInfoCollection} exported by the rule */
  ProviderInfoCollection getProviderInfos();
}
