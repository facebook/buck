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

package unit;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.intellij.plugin.ui.BuckEventsConsumer;
import com.intellij.mock.MockProjectEx;
import com.intellij.openapi.extensions.Extensions;

import org.junit.Test;

import java.lang.reflect.Field;

import javax.swing.tree.DefaultTreeModel;

import unit.util.MockDisposable;

public class BuckEventsConsumerTest {
  @Test
  public void hasBuckModuleAttachReceivedNullTargetThenWeShowNone()
      throws NoSuchFieldException, IllegalAccessException {
    Extensions.registerAreaClass("IDEA_PROJECT", null);
    BuckEventsConsumer buckEventsConsumer =
        new BuckEventsConsumer(
            new MockProjectEx(new MockDisposable()));

    buckEventsConsumer.attach(null, new DefaultTreeModel(null));


    Field privateStringField = BuckEventsConsumer.class.
        getDeclaredField("mTarget");

    privateStringField.setAccessible(true);

    String fieldValue = (String) privateStringField.get(buckEventsConsumer);
    System.out.println(fieldValue);
    assertEquals(fieldValue, "NONE");
  }
}
