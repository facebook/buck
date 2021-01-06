/*
 * Copyright 2014-present Facebook, Inc.
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

package exotest;

import android.content.Context;
import android.test.InstrumentationTestCase;
import org.junit.Test;

// We specifically use this outdated case to avoid a dep on the support lib
public class ThisIsATest extends InstrumentationTestCase {
  @Test
  public void testLoading() throws Exception {
    Context context = getInstrumentation().getContext();
  }
}
