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

package com.facebook.buck.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import com.google.common.util.concurrent.Runnables;
import java.lang.Thread.State;
import org.junit.Test;

/** Unit tests for {@link Threads} class. */
public class ThreadsTest {

  @Test
  public void testNamedThread() {
    String name = "test";
    Runnable runnable = Runnables.doNothing();

    Thread thread = Threads.namedThread(name, runnable);

    assertNotNull(thread);
    assertFalse(thread.isDaemon());
    assertEquals(State.NEW, thread.getState());
    assertEquals(name, thread.getName());
  }

  @Test(expected = NullPointerException.class)
  public void testNamedThreadWithNullName() {
    Threads.namedThread(null, Runnables.doNothing());
  }

  @Test
  public void testNamedThreadWithNullJob() {
    assertNotNull(Threads.namedThread("test", null));
  }
}
