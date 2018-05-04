/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.core.exceptions.handler;

import com.google.common.collect.ImmutableList;

/**
 * Central registry to manage different kinds of exceptions thrown to Buck Main class level, which
 * will unwrap the exceptions when necessary to make exceptions more friendly and readable to users
 */
public class ExceptionHandlerRegistry<R> {
  private ImmutableList<ExceptionHandler<? extends Throwable, R>> handlers;
  private ExceptionHandler<Throwable, R> genericHandler;

  public ExceptionHandlerRegistry(
      ImmutableList<ExceptionHandler<? extends Throwable, R>> handlers,
      ExceptionHandler<Throwable, R> genericHandler) {
    this.handlers = handlers;
    this.genericHandler = genericHandler;
  }

  /**
   * @param t the exception to handle
   * @return the exit code Buck should return to user
   *     <p>This method tries to find a registered {@link ExceptionHandler} that can process the
   *     input Exception, and calls that handler to process the exception. If it cannot find one, it
   *     will try to unwrap the exception by calling getCause() to get underlying exceptions until
   *     it succeeds or when there is no more cause to look into.
   *     <p>NOTE: This method does not detect loop in the exception chain.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public R handleException(Throwable t) {
    Throwable e = t;
    while (e != null) {
      for (ExceptionHandler eh : handlers) {
        if (eh.canHandleException(e)) {
          return (R) (eh.handleException(e));
        }
      }
      if (e == e.getCause()) {
        break;
      }
      e = e.getCause();
    }
    // none of the registered handlers could handle this Throwable
    return genericHandler.handleException(t);
  }
}
