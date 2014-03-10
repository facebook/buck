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
    public static void sink(Object x) {
        // Do nothing.
    }

    public static void test() {
        sink(new boolean[0]);
        sink(new byte[1]);
        sink(new char[2]);
        sink(new short[3]);
        sink(new int[4]);
        sink(new long[5]);
        sink(new float[6]);
        sink(new double[7]);
        sink(new Object[0]);
    }
}
