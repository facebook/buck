/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.slb;

import com.facebook.buck.event.BuckEventBus;
import java.io.IOException;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class RetryingHttpServiceTest {

  private BuckEventBus eventBus;
  private HttpService mockService;

  @Before
  public void setUp() {
    eventBus = EasyMock.createNiceMock(BuckEventBus.class);
    mockService = EasyMock.createMock(HttpService.class);
    mockService.close();
    EasyMock.expectLastCall();
  }

  @Test
  public void testRetryOnce() throws IOException {
    EasyMock.expect(mockService.makeRequest(EasyMock.isNull(), EasyMock.isNull()))
        .andThrow(new IOException())
        .once();
    EasyMock.expect(mockService.makeRequest(EasyMock.isNull(), EasyMock.isNull()))
        .andReturn(null)
        .once();
    EasyMock.replay(mockService);

    try (RetryingHttpService service = createRetryingService(1)) {
      HttpResponse response = service.makeRequest(null, null);
      Assert.assertNull(response);
    }

    EasyMock.verify(mockService);
  }

  @Test
  public void testRetryOnceWithRetryInterval() throws IOException {
    EasyMock.expect(mockService.makeRequest(EasyMock.isNull(), EasyMock.isNull()))
        .andThrow(new IOException())
        .once();
    EasyMock.expect(mockService.makeRequest(EasyMock.isNull(), EasyMock.isNull()))
        .andReturn(null)
        .once();
    EasyMock.replay(mockService);

    try (RetryingHttpService service =
        new RetryingHttpService(eventBus, mockService, "counterCategory", 1, 1)) {
      HttpResponse response = service.makeRequest(null, null);
      Assert.assertNull(response);
    }

    EasyMock.verify(mockService);
  }

  @Test
  public void testAllRetriesFailed() throws IOException {
    String errorMessage = "Super cool and amazing error msg.";
    EasyMock.expect(mockService.makeRequest(EasyMock.isNull(), EasyMock.isNull()))
        .andThrow(new IOException(errorMessage))
        .times(2);
    EasyMock.replay(mockService);

    try (RetryingHttpService service = createRetryingService(1)) {
      try {
        service.makeRequest(null, null);
        Assert.fail("An exception should've been thrown since all retries failed.");
      } catch (RetryingHttpService.RetryingHttpServiceException exception) {
        Assert.assertTrue(exception.getMessage().contains(errorMessage));
      }
    }

    EasyMock.verify(mockService);
  }

  @Test
  public void testNumberOfRetriesNeedsToBeNotNegative() {
    // Zero argument is fine.
    RetryingHttpService service1 = createRetryingService(0);
    service1.close();

    // Positive argument is fine.
    RetryingHttpService service2 = createRetryingService(1);
    service2.close();

    try (RetryingHttpService service3 = createRetryingService(-1)) {
      Assert.fail("The argument is not legal ao an exception should be thrown.");
    } catch (IllegalArgumentException exception) {
      Assert.assertTrue(exception.getMessage().contains("-1"));
    }
  }

  private RetryingHttpService createRetryingService(int retryCount) {
    return new RetryingHttpService(eventBus, mockService, "counterCategory", retryCount);
  }
}
