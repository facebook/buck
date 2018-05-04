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

package com.facebook.buck.cli.exceptions.handlers;

import com.facebook.buck.core.exceptions.handler.ExceptionHandler;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.InterruptionFailedException;
import com.martiansoftware.nailgun.NGContext;
import java.util.Optional;

/** Exception handler for {@link InterruptionFailedException} */
public class InterruptionFailedExceptionHandler
    extends ExceptionHandler<InterruptionFailedException, ExitCode> {

  private Optional<NGContext> ngContext;

  public InterruptionFailedExceptionHandler(Optional<NGContext> ngContext) {
    super(InterruptionFailedException.class);
    this.ngContext = ngContext;
  }

  @Override
  public ExitCode handleException(InterruptionFailedException e) {
    ngContext.ifPresent(c -> c.getNGServer().shutdown(false));
    return ExitCode.SIGNAL_INTERRUPT;
  }
}
