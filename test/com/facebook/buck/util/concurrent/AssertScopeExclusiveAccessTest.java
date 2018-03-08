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

package com.facebook.buck.util.concurrent;

import java.util.NoSuchElementException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AssertScopeExclusiveAccessTest {
  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void noExceptionOnSingleScope() {
    AssertScopeExclusiveAccess singleThreadedAccess = new AssertScopeExclusiveAccess();
    AssertScopeExclusiveAccess.Scope scope = singleThreadedAccess.scope();
    scope.close();
  }

  @Test
  public void exceptionOnTwoScopes() {
    AssertScopeExclusiveAccess singleThreadedAccess = new AssertScopeExclusiveAccess();

    try (AssertScopeExclusiveAccess.Scope scope = singleThreadedAccess.scope()) {
      expectedException.expect(NoSuchElementException.class);
      AssertScopeExclusiveAccess.Scope scope2 = singleThreadedAccess.scope();
      scope2.close();
    }
  }
}
