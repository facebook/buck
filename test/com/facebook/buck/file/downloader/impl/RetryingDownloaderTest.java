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

package com.facebook.buck.file.downloader.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.file.downloader.Downloader;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Tests {@link com.facebook.buck.file.downloader.impl.RetryingDownloader} functionality. */
public class RetryingDownloaderTest {

  private BuckEventBus fakeEventBus;
  private Path fakePath;
  private URI fakeUri;
  private Downloader downloader;

  @Before
  public void setUp() throws URISyntaxException {
    fakeEventBus = EasyMock.createMock(BuckEventBus.class);
    fakeUri = new URI("http://example.org");
    fakePath = EasyMock.createMock(Path.class);
    downloader = EasyMock.createMock(Downloader.class);
  }

  @After
  public void tearDown() {
    EasyMock.verify(downloader);
  }

  @Test
  public void fetchStopsAfterFirstReturnedTrue() throws Exception {
    EasyMock.expect(downloader.fetch(fakeEventBus, fakeUri, fakePath)).andReturn(true).once();

    EasyMock.replay(downloader);

    assertTrue(RetryingDownloader.from(downloader, 3).fetch(fakeEventBus, fakeUri, fakePath));
  }

  @Test
  public void fetchStopsAfterFirstReturnedFalse() throws Exception {
    EasyMock.expect(downloader.fetch(fakeEventBus, fakeUri, fakePath)).andReturn(false).once();

    EasyMock.replay(downloader);

    assertFalse(RetryingDownloader.from(downloader, 3).fetch(fakeEventBus, fakeUri, fakePath));
  }

  @Test
  public void fetchStopsAfterFirstSuccessAfterFailure() throws Exception {
    EasyMock.expect(downloader.fetch(fakeEventBus, fakeUri, fakePath))
        .andThrow(new IOException())
        .andReturn(true);

    EasyMock.replay(downloader);

    assertTrue(RetryingDownloader.from(downloader, 3).fetch(fakeEventBus, fakeUri, fakePath));
  }

  @Test(expected = RetryingDownloader.RetryingDownloaderException.class)
  public void fetchThrowsAfterMaxRetries() throws Exception {
    EasyMock.expect(downloader.fetch(fakeEventBus, fakeUri, fakePath))
        .andThrow(new IOException())
        .times(4);

    EasyMock.replay(downloader);

    RetryingDownloader.from(downloader, 3).fetch(fakeEventBus, fakeUri, fakePath);
  }
}
