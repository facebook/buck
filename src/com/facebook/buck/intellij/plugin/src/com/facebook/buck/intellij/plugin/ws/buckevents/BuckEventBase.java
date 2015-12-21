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

package com.facebook.buck.intellij.plugin.ws.buckevents;

import com.facebook.buck.intellij.plugin.ws.buckevents.parts.PartEventKey;
import java.math.BigInteger;

abstract class BuckEventBase implements
        BuckEventInterface {

    protected BigInteger timestamp;
    protected BigInteger nanoTime;
    protected int threadId;
    protected String buildId;

    public class BuckEventKey {
        public int value;
    }
    public PartEventKey eventKey;
    public String type;

    // For comparing
    public static final int PRIORITY_LOW = 0;
    public static final int PRIORITY_MED = 1;
    public static final int PRIORITY_HIGH = 2;

    @Override
    public int getPriority() {
        return PRIORITY_LOW;
    }

    public int compareTo(BuckEventInterface compareToObject) {
        BigInteger compareToValue = ((BuckEventBase ) compareToObject).timestamp;
        return this.timestamp.compareTo(compareToValue);
    }
}
