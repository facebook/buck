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

package com.android.dx.util;

import junit.framework.TestCase;

public final class IntListTest extends TestCase {
    public void test_contains() {
        for (int sz = 0; sz < 100; sz++) {
            IntList list = new IntList(sz);
            for (int i = 0; i < sz; i++) {
                list.add(i * 2);
            }
            for (int i = (sz * 2) - 1; i >= 0; i--) {
                boolean contains = list.contains(i);
                if ((i & 1) == 0) {
                    assertTrue(label(sz, i), contains);
                } else {
                    assertFalse(label(sz, i), contains);
                }
            }
            assertFalse(label(sz, -1), list.contains(-1));
            assertFalse(label(sz, sz * 2), list.contains(sz * 2));
        }
    }

    public void test_addSorted() {
        IntList list = new IntList(2);

        list.add(9);
        list.add(12);

        assertTrue(list.contains(9));
        assertTrue(list.contains(12));
    }

    public void test_addUnsorted() {
        IntList list = new IntList(2);

        list.add(12);
        list.add(9);

        assertTrue(list.contains(12));
        assertTrue(list.contains(9));
    }

    private static String label(int n, int m) {
        return "(" + n + "/" + m + ")";
    }
}
