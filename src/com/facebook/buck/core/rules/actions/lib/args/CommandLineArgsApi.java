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

package com.facebook.buck.core.rules.actions.lib.args;

import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;

/**
 * Simple interface to expose {@link com.facebook.buck.core.rules.actions.lib.args.CommandLineArgs}
 * to skylark. It cannot actually be used by anything in skylark directly
 */
public interface CommandLineArgsApi extends SkylarkValue {
  @Override
  default void repr(SkylarkPrinter printer) {
    printer.append("<command line arguments>");
  }

  @Override
  default boolean isImmutable() {
    /**
     * We already validate that the types added here are Immutable in {@link CommandLineArgsFactory}
     * there is no need to do further validation.
     *
     * <p>See also {@link AggregateCommandLineArgs}, {@link ListCommandLineArgs}, {@link
     * com.facebook.buck.core.rules.providers.lib.RunInfo}
     */
    return true;
  }
}
