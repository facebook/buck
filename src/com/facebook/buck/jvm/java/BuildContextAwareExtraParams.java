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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/** Extra params with Build Context. Currently used in Groovy, Kotlin and Scala. */
@BuckStyleValue
public abstract class BuildContextAwareExtraParams implements CompileToJarStepFactory.ExtraParams {

  public abstract BuildContext getBuildContext();

  public static BuildContextAwareExtraParams of(BuildContext buildContext) {
    return ImmutableBuildContextAwareExtraParams.ofImpl(buildContext);
  }
}
