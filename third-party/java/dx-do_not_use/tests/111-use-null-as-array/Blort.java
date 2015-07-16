/*
 * Copyright (C) 2009 The Android Open Source Project
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

public class Blort {
    public static boolean test_getBooleanArray() {
        boolean[] arr = null;
        return arr[1];
    }

    public static byte test_getByteArray() {
        byte[] arr = null;
        return arr[2];
    }

    public static char test_getCharArray() {
        char[] arr = null;
        return arr[3];
    }

    public static double test_getDoubleArray() {
        double[] arr = null;
        return arr[4];
    }

    public static float test_getFloatArray() {
        float[] arr = null;
        return arr[5];
    }

    public static int test_getIntArray() {
        int[] arr = null;
        return arr[6];
    }

    public static long test_getLongArray() {
        long[] arr = null;
        return arr[7];
    }

    public static Object test_getObjectArray() {
        Object[] arr = null;
        return arr[8];
    }

    public static short test_getShortArray() {
        short[] arr = null;
        return arr[9];
    }

    public static void test_setBooleanArray() {
        boolean[] arr = null;
        arr[1] = true;
    }

    public static void test_setByteArray() {
        byte[] arr = null;
        arr[2] = (byte) 3;
    }

    public static void test_setCharArray() {
        char[] arr = null;
        arr[4] = (char) 5;
    }

    public static void test_setDoubleArray() {
        double[] arr = null;
        arr[6] = 7.0F;
    }

    public static void test_setFloatArray() {
        float[] arr = null;
        arr[8] = 9.0F;
    }

    public static void test_setIntArray() {
        int[] arr = null;
        arr[10] = 11;
    }

    public static void test_setLongArray() {
        long[] arr = null;
        arr[12] = 13;
    }

    public static void test_setObjectArray() {
        Object[] arr = null;
        arr[14] = "blort";
    }

    public static void test_setShortArray() {
        short[] arr = null;
        arr[15] = (short) 16;
    }
}
