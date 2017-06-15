/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.doctor;

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

/** Responsible for getting extra information to the report by running a user-specified command. */
public interface ExtraInfoCollector {

  Optional<ExtraInfoResult> run()
      throws IOException, InterruptedException, ExtraInfoExecutionException;

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractExtraInfoResult {
    String getOutput();

    ImmutableSet<Path> getExtraFiles();
  }

  class ExtraInfoExecutionException extends Exception {
    public ExtraInfoExecutionException(String message) {
      super(message);
    }

    public ExtraInfoExecutionException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
