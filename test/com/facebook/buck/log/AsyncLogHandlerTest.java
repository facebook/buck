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

package com.facebook.buck.log;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.eq;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.easymock.EasyMock;
import org.junit.Test;

public class AsyncLogHandlerTest {
  @Test
  public void testAsyncLogHandlerForwardCallsAndUsesCorrectThreads() throws InterruptedException {
    // Create an Executor to perform asynchronously operations.
    // CountDownLatches are used to ensure that the executor received an async job
    // from the AsyncLogHandler, and that this completed successfully.
    AtomicLong expectedThreadId = new AtomicLong(-1);
    AtomicBoolean unexpectedException = new AtomicBoolean(false);
    CountDownLatch asyncProcessorCompletedLatch = new CountDownLatch(1);
    Executor executor = Executors.newSingleThreadExecutor();
    Executor executorDecorator =
        (orginalRunnable) -> {
          executor.execute(
              () -> {
                // Ensures that initially all calls to the Handler delegate happen asynchronously
                // (via the ExecutorService running this current piece of code).
                expectedThreadId.set(Thread.currentThread().getId());

                try {
                  orginalRunnable.run();
                } catch (Exception ex) {
                  unexpectedException.set(true);
                  ex.printStackTrace();
                }

                asyncProcessorCompletedLatch.countDown();
              });
        };

    // Mock that will be used to ensure the correct calls are forwarded to the delegate Handler
    Handler delegateHandlerMock = createMock(Handler.class);

    // This Handler decorates the above mock, and ensures that calls to the delegate
    // Handler are always made from the correct Thread.
    // Initially expectedThreadId is set to the Thread ID of the Executor (i.e. asynchronous),
    // and after close() is called it gets set to the Thread ID of this test (i.e. synchronous).
    AtomicBoolean correctThreadUsed = new AtomicBoolean(true);
    Handler threadAwareHandlerDecorator =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            assertThreadId();
            delegateHandlerMock.publish(record);
          }

          @Override
          public void flush() {
            assertThreadId();
            delegateHandlerMock.flush();
          }

          @Override
          public void close() throws SecurityException {
            assertThreadId();
            delegateHandlerMock.close();
          }

          // This ensures that all received calls happen on the thread matching expectedThreadId.
          private synchronized void assertThreadId() {
            boolean currentCallUsedCorrectThread =
                expectedThreadId.get() == Thread.currentThread().getId();
            correctThreadUsed.set(correctThreadUsed.get() && currentCallUsedCorrectThread);
          }
        };

    // Create the AsyncLogHandler that we are testing
    AsyncLogHandler asyncLogHandler =
        new AsyncLogHandler(() -> executorDecorator, threadAwareHandlerDecorator);

    // Test data
    LogRecord logRecordOne = new LogRecord(Level.INFO, "message one");
    LogRecord logRecordTwo = new LogRecord(Level.INFO, "message two");
    LogRecord logRecordThree = new LogRecord(Level.INFO, "message three");

    // Setup mock expectations for async calls
    delegateHandlerMock.publish(eq(logRecordOne));
    delegateHandlerMock.publish(eq(logRecordTwo));
    delegateHandlerMock.close();
    delegateHandlerMock.publish(anyObject(LogRecord.class)); // "Finished shutting down.."

    // Setup mock expectations for sync calls
    delegateHandlerMock.publish(eq(logRecordThree));
    delegateHandlerMock.flush();

    EasyMock.replay(delegateHandlerMock);

    /** Async calls * */

    // Send initial log request. This should enable the async processor.
    asyncLogHandler.publish(logRecordOne);

    // Send a different log request.
    asyncLogHandler.publish(logRecordTwo);

    // Tell the async handler to close. This will also publish a message "Finished shutting down"
    asyncLogHandler.close();

    // Ensure async processor started and then completed.
    assertTrue(asyncProcessorCompletedLatch.await(1000, TimeUnit.MILLISECONDS));

    // Ensure there were no exceptions thrown in other thread
    assertFalse(unexpectedException.get());

    // Ensure that all calls to the Handler so far happened asynchronously
    // (i.e. from inside the ExecutorService's Thread)
    assertTrue(correctThreadUsed.get());

    /** Sync calls * */

    // All remaining calls should happen on the same Thread as this test
    expectedThreadId.set(Thread.currentThread().getId());

    // Make a log request, and a flush request.
    asyncLogHandler.publish(logRecordThree);
    asyncLogHandler.flush();

    // Ensure that the final two calls were made to the Handler synchronously
    assertTrue(correctThreadUsed.get());

    EasyMock.verify(delegateHandlerMock);
  }
}
