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
    private volatile int i;
    private volatile long l;
    private volatile float f;
    private volatile double d;

    public void blort(int i1, int i2) {
        i = -i1;
        i = ~i1;
        i = i1 + i2;
        i = i1 - i2;
        i = i1 * i2;
        i = i1 / i2;
        i = i1 % i2;
        i = i1 & i2;
        i = i1 | i2;
        i = i1 ^ i2;
        i = i1 << i2;
        i = i1 >> i2;
        i = i1 >>> i2;
    }

    public void blort(long l1, long l2) {
        l = -l1;
        l = ~l1;
        l = l1 + l2;
        l = l1 - l2;
        l = l1 * l2;
        l = l1 / l2;
        l = l1 % l2;
        l = l1 & l2;
        l = l1 | l2;
        l = l1 ^ l2;
        l = l1 << l2;
        l = l1 >> l2;
        l = l1 >>> l2;
    }

    public void blort(float f1, float f2) {
        f = -f1;
        f = f1 + f2;
        f = f1 - f2;
        f = f1 * f2;
        f = f1 / f2;
        f = f1 % f2;
    }

    public void blort(double d1, double d2) {
        d = -d1;
        d = d1 + d2;
        d = d1 - d2;
        d = d1 * d2;
        d = d1 / d2;
        d = d1 % d2;
    }
}
