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

package com.facebook.buck.event;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LogviewFormatterTest {

  @Test
  public void throwableLogviewFormatting() {
    String message = "Message.";
    Throwable cause = new Throwable();

    Throwable t1 = new Throwable(message);
    Iterable<String> formattedStack1 = LogviewFormatter.format(t1);
    assertTrue(formattedStack1.toString().startsWith("[java.lang.Throwable: Message."));

    Throwable t2 = new Throwable(message, cause);
    Iterable<String> formattedStack2 = LogviewFormatter.format(t2);
    assertTrue(formattedStack2.toString().startsWith("[java.lang.Throwable: Message."));
    assertTrue(formattedStack2.toString().contains("Caused by: java.lang.Throwable"));

    Throwable t3 = new Throwable(cause);
    Iterable<String> formattedStack3 = LogviewFormatter.format(t3);
    assertTrue(formattedStack3.toString().startsWith("[java.lang.Throwable: java.lang.Throwable"));
    assertTrue(formattedStack3.toString().contains("Caused by: java.lang.Throwable"));
  }
}
