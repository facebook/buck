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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;

/**
 * Abstract Base class for non-compilation daemon compatible compile to JAR factories that need
 * access to the BuildContext to produce their steps.
 */
public abstract class BuildContextAwareCompileToJarStepFactory
    extends CompileToJarStepFactory<BuildContextAwareExtraParams>
    implements CompileToJarStepFactory.CreatesExtraParams<BuildContextAwareExtraParams> {

  protected BuildContextAwareCompileToJarStepFactory(
      boolean hasAnnotationProcessing, boolean withDownwardApi) {
    super(hasAnnotationProcessing, withDownwardApi);
  }

  @Override
  public Class<BuildContextAwareExtraParams> getExtraParamsType() {
    return BuildContextAwareExtraParams.class;
  }

  @Override
  public BuildContextAwareExtraParams createExtraParams(BuildContext context, AbsPath _rootPath) {
    return BuildContextAwareExtraParams.of(context);
  }
}
