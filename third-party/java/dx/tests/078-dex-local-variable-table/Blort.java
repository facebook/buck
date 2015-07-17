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
    public static void test01(Object x) {
        x.hashCode();
    }

    public static Object test02() {
        Object[] arr = null;
        return arr[0];
    }

    public static String test03(int x) {
        String foo = null;
        return foo;
    }

    public static String test04(int x) {
        String foo = null;
        if (x < 0) {
            foo = "bar";
        }
        return foo;
    }

    public static int test05(Object x) {
        int[] arr = (int[]) x;
        arr[0] = 123;
        return arr[0];
    }

    public static int test06(int x) {
        if (x < 10) {
            int y = 1;
            return y;
        } else {
            int y = 2;
            return y;
        }
    }

    // Test for representation of boolean.
    public static void test07(boolean x) {
        boolean y = x;
    }

    // Test for representation of byte.
    public static void test08(byte x) {
        byte y = x;
    }

    // Test for representation of char.
    public static void test09(char x) {
        char y = x;
    }

    // Test for representation of double.
    public static void test10(double x) {
        double y = x;
    }

    // Test for representation of float.
    public static void test11(float x) {
        float y = x;
    }

    // Test for representation of int.
    public static void test12(int x) {
        int y = x;
    }

    // Test for representation of long.
    public static void test13(long x) {
        long y = x;
    }

    // Test for representation of short.
    public static void test14(short x) {
        short y = x;
    }

    // Test for representation of Object.
    public static void test15(Object x) {
        Object y = x;
    }

    // Test for representation of String (as a token example of a non-Object
    // reference type).
    public static void test16(String x) {
        String y = x;
    }

    // Test for representation of int[] (as a token example of an array class).
    public static void test17(int[] x) {
        int[] y = x;
    }
}
