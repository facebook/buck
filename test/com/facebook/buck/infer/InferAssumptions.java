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

package com.facebook.buck.infer;

import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.infer.toolchain.InferPlatformFactory;
import com.facebook.buck.io.ExecutableFinder;
import java.util.Optional;

public class InferAssumptions {
  public static void assumeInferIsConfigured() {
    Optional<UnresolvedInferPlatform> unresolvedPlatform =
        InferPlatformFactory.getBasedOnConfigAndPath(
            FakeBuckConfig.builder().build(), new ExecutableFinder());

    assumeTrue("infer is not available", unresolvedPlatform.isPresent());
  }
}
