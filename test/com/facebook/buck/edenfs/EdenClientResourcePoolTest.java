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

package com.facebook.buck.edenfs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import org.junit.Test;

public class EdenClientResourcePoolTest {

  @Test
  public void test() throws Exception {
    ArrayList<TestEdenClientResource> created = new ArrayList<>();
    EdenClientResourcePool pool =
        new EdenClientResourcePool(
            () -> {
              TestEdenClientResource resource = new TestEdenClientResource(new TestEdenClient());
              created.add(resource);
              return resource;
            });

    EdenClientResource client = pool.openClient();
    assertEquals(1, created.size());
    EdenClient edenClient = client.getEdenClient();

    EdenClientResource client2 = pool.openClient();
    assertEquals(2, created.size());
    client.close();

    // Client returned to the pool
    assertEquals(1, pool.pool.size());

    EdenClientResource clientAgain = pool.openClient();

    // Returned previously client
    assertEquals(0, pool.pool.size());
    assertSame(edenClient, clientAgain.getEdenClient());

    clientAgain.close();
    client2.close();

    // Everything returned to the pool
    assertEquals(2, pool.pool.size());

    pool.close();

    // Everything's closed, pool empty
    assertEquals(0, pool.pool.size());
  }
}
