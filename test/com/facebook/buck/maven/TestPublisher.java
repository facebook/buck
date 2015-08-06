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

package com.facebook.buck.maven;

import com.facebook.buck.testutil.integration.HttpdForTests;
import com.google.common.base.Optional;

import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

/**
 * A {@link com.facebook.buck.maven.Publisher}, that does not send real PUT requests, instead
 * recording their paths
 *
 * Use {@link #getPutRequestsHandler}.getPutRequestsPaths to get the paths of PUT requests invoked
 */
public class TestPublisher extends Publisher implements AutoCloseable {

  private HttpdForTests httpd;
  private HttpdForTests.DummyPutRequestsHandler putRequestsHandler;

  public static TestPublisher create(TemporaryFolder tmpDir) throws Exception {
    return create(tmpDir.newFolder().toPath());
  }

  /**
   * @param pseudoLocalRepo typically {@link org.junit.rules.TemporaryFolder#newFolder}
   */
  public static TestPublisher create(Path pseudoLocalRepo) throws Exception {
    HttpdForTests.DummyPutRequestsHandler putRequestsHandler =
        new HttpdForTests.DummyPutRequestsHandler();
    HttpdForTests httpd = new HttpdForTests();
    httpd.addHandler(putRequestsHandler);
    httpd.start();
    return new TestPublisher(pseudoLocalRepo, httpd, putRequestsHandler);
  }

  private TestPublisher(
      Path pseudoLocalRepo,
      HttpdForTests httpd,
      HttpdForTests.DummyPutRequestsHandler putRequestsHandler) throws Exception {
    super(pseudoLocalRepo, Optional.of(httpd.getRootUri().toURL()), /* dryRun */ false);
    this.httpd = httpd;
    this.putRequestsHandler = putRequestsHandler;
  }

  @Override
  public void close() throws Exception {
    httpd.close();
  }

  public HttpdForTests getHttpd() {
    return httpd;
  }

  public HttpdForTests.DummyPutRequestsHandler getPutRequestsHandler() {
    return putRequestsHandler;
  }
}
