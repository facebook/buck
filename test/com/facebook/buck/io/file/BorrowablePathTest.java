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

package com.facebook.buck.io.file;

import static org.hamcrest.junit.MatcherAssert.assertThat;

import java.io.File;
import org.hamcrest.Matchers;
import org.junit.Test;

public class BorrowablePathTest {

  @Test
  public void testExplicitBorrow() {
    BorrowablePath p = BorrowablePath.borrowablePath(new File("/tmp/path").toPath());
    assertThat(p.canBorrow(), Matchers.equalTo(true));
  }

  @Test
  public void testExplicitNotBorrow() {
    BorrowablePath p = BorrowablePath.notBorrowablePath(new File("/tmp/path").toPath());
    assertThat(p.canBorrow(), Matchers.equalTo(false));
  }
}
