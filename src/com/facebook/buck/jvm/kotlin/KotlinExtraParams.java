/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.jvm.kotlin;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;

/** Extra params for creating Kotlin compile steps. */
@BuckStyleValue
public abstract class KotlinExtraParams implements CompileToJarStepFactory.ExtraParams {

  public abstract BuildContext getBuildContext();

  /** Resolve extra params. */
  public static KotlinExtraParams of(BuildContext buildContext) {
    return ImmutableKotlinExtraParams.ofImpl(buildContext);
  }
}
