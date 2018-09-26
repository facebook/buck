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

package com.facebook.buck.remoteexecution.thrift;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.Threads;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.util.function.ThrowingFunction;
import com.facebook.thrift.transport.TTransportException;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * A thrift client helper which introduces a method to retry RPC calls on network failures
 * (IOException / TTransportException). Users of this class should use the method {@link
 * RetryingSynchronizedThriftHelper ::retryOnNetworkException} to implement the RPC calls they want
 * to retry.
 *
 * @param <ClientT> RPC client object which can be re-created upon failure using the provided
 *     supplier.
 */
public class RetryingSynchronizedThriftHelper<ClientT> {
  private static final Logger LOG = Logger.get(RetryingSynchronizedThriftHelper.class);

  @GuardedBy("this")
  private final Supplier<ClientT> clientSupplier;

  private final int[] retryBackoffMillis;

  @GuardedBy("this")
  @Nullable
  private ClientT currentClient;

  public RetryingSynchronizedThriftHelper(
      Supplier<ClientT> clientSupplier, int[] retryBackoffMillis) {
    this.clientSupplier = clientSupplier;
    this.retryBackoffMillis = retryBackoffMillis;
    createNewClient();
  }

  private synchronized void createNewClient() {
    currentClient = clientSupplier.get();
  }

  private void backOffDueToFailure(int iter) {
    try {
      Thread.sleep(retryBackoffMillis[iter]);
    } catch (InterruptedException interrupt) {
      Threads.interruptCurrentThread(); // Re-set the interrupt flag.
      throw new BuckUncheckedExecutionException(interrupt);
    }
  }

  /**
   * Users of this class should use this method to implement RPC calls for which they want to retry
   * on a network failure (IOException / TTransportException).
   *
   * @param foo RPC call to retry.
   * @return Object returned from a successful invocation of {@param foo}.
   * @throws Exception Last captured network exception if we run out of retries, or any other type
   *     of exception that was thrown by {@param foo}.
   */
  public synchronized <ReturnT> ReturnT retryOnNetworkException(
      ThrowingFunction<ClientT, ReturnT, Exception> foo) throws Exception {

    if (currentClient == null) {
      createNewClient();
    }

    for (int numIter = 0; true; ++numIter) {
      try {
        return foo.apply(Preconditions.checkNotNull(currentClient));
      } catch (TTransportException | IOException e) {
        String msg =
            String.format(
                "Got network exception in attempt number [%d]. "
                    + "Creating a new connection for next retry. Details=[%s]",
                numIter + 1, ThriftUtil.getExceptionDetails(e));

        // Throw if we're out of retries.
        if (numIter >= retryBackoffMillis.length) {
          LOG.error(msg);
          currentClient = null;
          throw e;
        }

        // Otherwise continue the loop after backing off.
        LOG.warn(e, msg);
        backOffDueToFailure(numIter);
        createNewClient();
      }
    }
  }
}
