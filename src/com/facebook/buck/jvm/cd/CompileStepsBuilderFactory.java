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

package com.facebook.buck.jvm.cd;

import com.facebook.buck.jvm.cd.params.CDParams;

/** Factory that creates {@link CompileStepsBuilder} instances */
public interface CompileStepsBuilderFactory {

  /** Creates an appropriate {@link LibraryStepsBuilder} instance */
  LibraryStepsBuilder getLibraryBuilder();

  /** Creates an appropriate {@link AbiStepsBuilder} instance */
  AbiStepsBuilder getAbiBuilder();

  /**
   * Interface for creating builder factories. This abstraction is necessary so that JVM languages
   * that support compiler daemons can build steps that issue work to the daemon, while languages
   * that don't can build steps that run in the main buck process.
   */
  interface Creator {
    CompileStepsBuilderFactory createStepsBuilderFactory(CDParams params);
  }
}
