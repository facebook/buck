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
    public boolean test01(boolean[] x) {
        x[0] = true;
        return x[1];
    }

    public byte test02(byte[] x) {
        x[0] = 5;
        return x[1];
    }

    public short test03(short[] x) {
        x[0] = 5;
        return x[1];
    }

    public char test04(char[] x) {
        x[0] = 5;
        return x[1];
    }

    public int test05(int[] x) {
        x[0] = 5;
        return x[1];
    }

    public long test06(long[] x) {
        x[0] = 5;
        return x[1];
    }

    public float test07(float[] x) {
        x[0] = 2.0f;
        return x[1];
    }

    public double test08(double[] x) {
        x[0] = 2.0;
        return x[1];
    }

    public Object test09(Object[] x) {
        x[0] = null;
        return x[1];
    }

    public static Object test10(Object[][] x) {
        x[0][0] = null;
        return x[1][2];
    }

    public static int test11(Object x) {
        int[][][] arr = (int[][][]) x;
        arr[0][0][0] = 123;
        return arr[1][2][3];
    }
}
