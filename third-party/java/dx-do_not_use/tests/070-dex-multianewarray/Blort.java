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
    public static Object test01() {
        Object[][] x = new Object[2][5];
        return x;
    }

    public static Object test02() {
        Object[][][] x = new Object[4][1][];
        return x;
    }

    public static Object test03() {
        Object[][][] x = new Object[7][2][4];
        return x;
    }

    public static Object test04() {
        Object[][][] x = new Object[3][0][0];
        return x;
    }

    public static Object test05() {
        Object[][][][] x = new Object[1][3][5][7];
        return x;
    }

    public static Object test06() {
        Object[][][][][] x = new Object[8][7][2][3][4];
        return x;
    }

    public static Object test07() {
        Object[][][][][][] x = new Object[8][7][2][3][4][];
        return x;
    }

    public static Object test08() {
        Object[][][][][][][] x = new Object[8][7][2][3][4][][];
        return x;
    }

    public static boolean[][] test09() {
        return new boolean[1][2];
    }

    public static byte[][] test10() {
        return new byte[3][4];
    }

    public static char[][] test11() {
        return new char[5][6];
    }

    public static double[][] test12() {
        return new double[7][8];
    }

    public static float[][] test13() {
        return new float[9][1];
    }

    public static int[][][] test14() {
        return new int[5][3][2];
    }

    public static long[][][] test15() {
        return new long[3][4][7];
    }

    public static short[][][][] test16() {
        return new short[5][4][3][2];
    }

    public static String[][][][][] test17() {
        return new String[5][4][3][2][1];
    }

    public static Runnable[][][][][][] test18() {
        return new Runnable[5][4][3][2][1][8];
    }
}
