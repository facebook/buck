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

package com.facebook.buck.util.network;

import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.distributed.thrift.LogRequest;
import com.facebook.buck.distributed.thrift.LogRequestType;
import com.facebook.buck.distributed.thrift.ScribeData;
import com.facebook.buck.log.Logger;
import com.facebook.buck.slb.ThriftService;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.concurrent.Callable;

public class ThriftScribeLogger extends ScribeLogger {
  private static final Logger LOG = Logger.get(ThriftScribeLogger.class);


  private final ThriftService<FrontendRequest, FrontendResponse> thriftService;
  private final ListeningExecutorService executorService;

  public ThriftScribeLogger(
      ThriftService<FrontendRequest, FrontendResponse> thriftService,
      ListeningExecutorService executorService) {
    this.thriftService = thriftService;
    this.executorService = executorService;
  }

  @Override
  public ListenableFuture<Void> log(final String category, final Iterable<String> lines) {
    synchronized (executorService) {
      if (executorService.isShutdown()) {
        String errorMessage =
            String.format(
                "%s will not accept any more log calls because it has already been closed.",
                getClass());
        LOG.warn(errorMessage);
        return Futures.immediateFailedCheckedFuture(new IllegalStateException(errorMessage));
      }
    }

    return executorService.submit(
        new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            sendViaThrift(category, lines);
            return null;
          }
        });
  }

  private void sendViaThrift(String category, Iterable<String> lines) throws IOException {
    //Prepare log request.
    ScribeData scribeData = new ScribeData();
    scribeData.setCategory(category);
    copyLinesWithoutNulls(lines, scribeData);
    LogRequest logRequest = new LogRequest();
    logRequest.setType(LogRequestType.SCRIBE_DATA);
    logRequest.setScribeData(scribeData);

    // Wrap in high-level request & response.
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.LOG);
    request.setLogRequest(logRequest);
    FrontendResponse response = new FrontendResponse();

    thriftService.makeRequest(request, response);
    if (!response.isWasSuccessful()) {
      throw new IOException(
          String.format("Log request failed. Error from response: %s", response.getErrorMessage()));
    }
  }

  @VisibleForTesting
  static void copyLinesWithoutNulls(Iterable<String> lines, ScribeData scribeData) {
    int numberOfNullLines = 0;
    int totalLines = 0;
    for (String line : lines) {
      ++totalLines;
      if (line != null) {
        scribeData.addToLines(line);
      } else {
        ++numberOfNullLines;
      }
    }

    // TODO(ruibm): This way we get some signal where the null lines are coming from and still send
    // back as much non-corrupted data as we can.
    if (numberOfNullLines > 0) {
      LOG.error(
          String.format(
              "Out of [%d] log lines, [%d] were null for category [%s].",
              totalLines, numberOfNullLines, scribeData.getCategory()));
    }
  }

  @Override
  public void close() throws IOException {
    synchronized (executorService) {
      executorService.shutdown();
    }

    thriftService.close();
  }
}
