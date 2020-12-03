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

import com.facebook.buck.core.util.immutables.BuckStyleValue;

/**
 * Extra params instance used to create Java related compile build steps Doesn't hold {@link
 * com.facebook.buck.core.build.context.BuildContext}
 */
@BuckStyleValue
public abstract class JavaExtraParams implements CompileToJarStepFactory.ExtraParams {

  abstract ResolvedJavacOptions getResolvedJavacOptions();

  public static JavaExtraParams of(ResolvedJavacOptions resolvedJavacOptions) {
    return ImmutableJavaExtraParams.ofImpl(resolvedJavacOptions);
  }
}
