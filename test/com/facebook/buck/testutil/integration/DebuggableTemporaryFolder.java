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

package com.facebook.buck.testutil.integration;

import com.google.common.base.Preconditions;

import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

/**
 * Subclass of {@link TemporaryFolder} that optionally keeps the contents of the tmp around after
 * the test has finished. This is often useful when debugging a failed integration test.
 * <p>
 * Here is an example of how to create a {@link DebuggableTemporaryFolder} that will not delete
 * itself on exit in an integration test:
 * <pre>
 * &#64;Rule
 * public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder().doNotDeleteOnExit();
 * </pre>
 */
public class DebuggableTemporaryFolder extends TemporaryFolder {

  private String name;
  private boolean doNotDeleteOnExit;

  public DebuggableTemporaryFolder() {}

  /**
   * If invoked, the directory created by this {@link TemporaryFolder} will not be deleted when the
   * test finishes.
   * @return {@code this}
   */
  public DebuggableTemporaryFolder doNotDeleteOnExit() {
    this.doNotDeleteOnExit = true;
    return this;
  }

  /**
   * Name to use to identify this {@link TemporaryFolder} when writing log messages to stdout.
   * @return {@code this}
   */
  public DebuggableTemporaryFolder setName(String name) {
    this.name = Preconditions.checkNotNull(name);
    return this;
  }

  @Override
  public void after() {
    if (doNotDeleteOnExit) {
      String name = this.name == null ? "TemporaryFolder" : this.name;
      System.out.printf("%s available at %s.\n", name, getRoot());
    } else {
      super.after();
    }
  }

  public Path getRootPath() {
    return getRoot().toPath();
  }
}
