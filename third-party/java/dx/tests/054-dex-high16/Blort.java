/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

public class Blort
{
    public static void sink(int i) {
        // Do nothing.
    }

    public static void sink(long l) {
        // Do nothing.
    }

    public static void sink(float f) {
        // Do nothing.
    }

    public static void sink(double d) {
        // Do nothing.
    }

    public static void testInt() {
        sink(Integer.MIN_VALUE);
        sink(0x40000000);
        sink(0x20000000);
        sink(0x10000000);
        sink(0x00080000);
        sink(0x00040000);
        sink(0x00020000);
        sink(0x00010000);
        sink(0x56780000);
    }

    public static void testLong() {
        sink(Long.MIN_VALUE);
        sink(0x4000000000000000L);
        sink(0x2000000000000000L);
        sink(0x1000000000000000L);
        sink(0x0008000000000000L);
        sink(0x0004000000000000L);
        sink(0x0002000000000000L);
        sink(0x0001000000000000L);
        sink(0x5678000000000000L);
    }

    public static void testFloat() {
        sink(Float.NEGATIVE_INFINITY);
        sink(-0.0f);
        sink(1.0f);
        sink(Float.POSITIVE_INFINITY);
        sink(Float.NaN);
    }

    public static void testDouble() {
        sink(Double.NEGATIVE_INFINITY);
        sink(-0.0);
        sink(1.0);
        sink(Double.POSITIVE_INFINITY);
        sink(Double.NaN);
    }
}
