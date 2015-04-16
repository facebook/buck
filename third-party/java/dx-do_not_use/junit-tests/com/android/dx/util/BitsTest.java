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

public final class BitsTest extends TestCase {
    public void test_makeBitSet() {
        assertEquals(label(0), 0, Bits.makeBitSet(0).length);

        for (int i = 1; i <= 32; i++) {
            assertEquals(label(i), 1, Bits.makeBitSet(i).length);
        }

        for (int i = 33; i <= 64; i++) {
            assertEquals(label(i), 2, Bits.makeBitSet(i).length);
        }

        for (int i = 65; i < 4000; i += 101) {
            int expect = i >> 5;
            if ((expect * 32) < i) {
                expect++;
            }
            assertEquals(label(i), expect, Bits.makeBitSet(i).length);
        }
    }

    public void test_getMax() {
        for (int i = 0; i < 4000; i += 59) {
            int expect = i >> 5;
            if ((expect * 32) < i) {
                expect++;
            }
            assertEquals(label(i), expect * 32,
                         Bits.getMax(new int[expect]));
        }
    }

    public void test1_get() {
        int[] bits = Bits.makeBitSet(100);

        for (int i = 0; i < 100; i++) {
            assertFalse(label(i), Bits.get(bits, i));
        }
    }

    public void test2_get() {
        int[] bits = Bits.makeBitSet(100);
        for (int i = 0; i < bits.length; i++) {
            bits[i] = -1;
        }

        for (int i = 0; i < 100; i++) {
            assertTrue(label(i), Bits.get(bits, i));
        }
    }

    public void test3_get() {
        int[] bits = Bits.makeBitSet(100);

        for (int i = 0; i < 100; i++) {
            Bits.set(bits, i, (i % 5) == 0);
        }

        for (int i = 0; i < 100; i++) {
            boolean expect = (i % 5) == 0;
            assertTrue(label(i), Bits.get(bits, i) == expect);
        }
    }

    public void test1_set1() {
        int[] bits = Bits.makeBitSet(50);
        bits[1] = -1;

        Bits.set(bits, 0, true);
        Bits.set(bits, 3, true);
        Bits.set(bits, 6, true);
        Bits.set(bits, 3, false);
        Bits.set(bits, 35, false);
        Bits.set(bits, 38, false);
        Bits.set(bits, 42, false);
        Bits.set(bits, 38, true);

        assertEquals(label(1), 0x41, bits[0]);
        assertEquals(label(2), 0xfffffbf7, bits[1]);
    }

    public void test2_set1() {
        int[] bits = Bits.makeBitSet(100);

        for (int i = 0; i < 100; i++) {
            if ((i % 3) == 0) {
                Bits.set(bits, i, true);
            }
        }

        for (int i = 0; i < 100; i++) {
            if ((i % 5) == 0) {
                Bits.set(bits, i, false);
            }
        }

        for (int i = 0; i < 100; i++) {
            if ((i % 7) == 0) {
                Bits.set(bits, i, true);
            }
        }

        for (int i = 0; i < 100; i++) {
            boolean expect = ((i % 7) == 0) ||
                (((i % 3) == 0) && ((i % 5) != 0));
            assertTrue(label(i), Bits.get(bits, i) == expect);
        }
    }

    public void test_set2() {
        int[] bits = Bits.makeBitSet(100);

        for (int i = 0; i < 100; i++) {
            if ((i % 11) == 0) {
                Bits.set(bits, i);
            }
        }

        for (int i = 0; i < 100; i++) {
            boolean expect = (i % 11) == 0;
            assertTrue(label(i), Bits.get(bits, i) == expect);
        }
    }

    public void test_clear() {
        int[] bits = Bits.makeBitSet(100);
        for (int i = 0; i < bits.length; i++) {
            bits[i] = -1;
        }

        for (int i = 0; i < 100; i++) {
            if ((i % 5) == 0) {
                Bits.clear(bits, i);
            }
        }

        for (int i = 0; i < 100; i++) {
            boolean expect = (i % 5) != 0;
            assertTrue(label(i), Bits.get(bits, i) == expect);
        }
    }

    public void test1_isEmpty() {
        for (int i = 0; i < 10; i++) {
            assertTrue(label(i), Bits.isEmpty(new int[i]));
        }
    }

    public void test2_isEmpty() {
        for (int i = 1; i < 1000; i += 11) {
            int[] bits = Bits.makeBitSet(i);
            for (int j = i % 11; j >= 0; j--) {
                int x = i - 1 - (j * 13);
                if (x >= 0) {
                    Bits.set(bits, x);
                }
            }
            assertFalse(label(i), Bits.isEmpty(bits));
        }
    }

    public void test1_bitCount() {
        for (int i = 0; i < 10; i++) {
            assertEquals(label(i), 0, Bits.bitCount(new int[i]));
        }
    }

    public void test2_bitCount() {
        for (int i = 1; i < 1000; i += 13) {
            int[] bits = Bits.makeBitSet(i);
            int count = 0;
            for (int j = 0; j < i; j += 20) {
                Bits.set(bits, j);
                count++;
            }
            for (int j = 7; j < i; j += 11) {
                if (!Bits.get(bits, j)) {
                    Bits.set(bits, j);
                    count++;
                }
            }
            for (int j = 3; j < i; j += 17) {
                if (!Bits.get(bits, j)) {
                    Bits.set(bits, j);
                    count++;
                }
            }
            assertEquals(label(i), count, Bits.bitCount(bits));
        }
    }

    public void test1_anyInRange() {
        int[] bits = new int[100];

        for (int i = 0; i < 100; i += 11) {
            assertFalse(label(i), Bits.anyInRange(bits, 0, i));
        }
    }

    public void test2_anyInRange() {
        int[] bits = new int[100];

        for (int i = 0; i < 100; i += 11) {
            assertFalse(label(i), Bits.anyInRange(bits, i, 100));
        }
    }

    public void test3_anyInRange() {
        int[] bits = new int[100];

        for (int i = 0; i < 50; i += 7) {
            assertFalse(label(i), Bits.anyInRange(bits, i, 100 - i));
        }
    }

    public void test4_anyInRange() {
        int[] bits = new int[100];
        for (int i = 0; i < bits.length; i++) {
            bits[i] = -1;
        }

        for (int i = 1; i < 100; i += 11) {
            assertTrue(label(i), Bits.anyInRange(bits, 0, i));
        }
    }

    public void test5_anyInRange() {
        int[] bits = new int[100];
        for (int i = 0; i < bits.length; i++) {
            bits[i] = -1;
        }

        for (int i = 1; i < 100; i += 11) {
            assertTrue(label(i), Bits.anyInRange(bits, i, 100));
        }
    }

    public void test6_anyInRange() {
        int[] bits = new int[100];
        for (int i = 0; i < bits.length; i++) {
            bits[i] = -1;
        }

        for (int i = 0; i < 50; i += 7) {
            assertTrue(label(i), Bits.anyInRange(bits, i, 100 - i));
        }
    }

    public void test1_findFirst1() {
        int[] bits = new int[100];

        for (int i = 0; i < 100; i++) {
            assertEquals(label(i), -1, Bits.findFirst(bits, i));
        }
    }

    public void test2_findFirst1() {
        int[] bits = new int[100];
        for (int i = 0; i < bits.length; i++) {
            bits[i] = -1;
        }

        for (int i = 0; i < 100; i++) {
            assertEquals(label(i), i, Bits.findFirst(bits, i));
        }
    }

    public void test3_findFirst1() {
        int[] bits = new int[100];

        for (int i = 25; i < 80; i++) {
            for (int j = 0; j < bits.length; j++) {
                bits[j] = 0;
            }

            Bits.set(bits, i - 5);
            Bits.set(bits, i + 5);
            Bits.set(bits, i + 10);
            Bits.set(bits, i + 20);
            assertEquals(label(i), i + 5, Bits.findFirst(bits, i));
        }
    }

    public void test1_findFirst2() {
        for (int i = 0; i < 32; i++) {
            assertEquals(label(i), -1, Bits.findFirst(0, i));
        }
    }

    public void test2_findFirst2() {
        for (int i = 0; i < 32; i++) {
            assertEquals(label(i), i, Bits.findFirst(-1, i));
        }
    }

    public void test3_findFirst2() {
        for (int i = 0; i < 32; i++) {
            assertEquals(label(i), -1, Bits.findFirst((1 << i) >>> 1, i));
        }
    }

    public void test4_findFirst2() {
        for (int i = 0; i < 32; i++) {
            assertEquals(label(i), i, Bits.findFirst(1 << i, i));
        }
    }

    public void test5_findFirst2() {
        for (int i = 0; i < 31; i++) {
            assertEquals(label(i), i + 1, Bits.findFirst(1 << (i + 1), i));
        }
    }

    public void test6_findFirst2() {
        for (int i = 0; i < 32; i++) {
            int value = (1 << i);
            value |= (value >>> 1);
            assertEquals(label(i), i, Bits.findFirst(value, i));
        }
    }

    private static String label(int n) {
        return "(" + n + ")";
    }
}
