/*
 * Copyright 2019-present Facebook, Inc.
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
// MathClient.cpp : Defines the entry point for the console application.
// Compile by using: cl /EHsc /link MathLibrary.lib MathClient.cpp

#include <iostream>
#include "MathLibrary.h"

using namespace std;

int main()
{
    double a = 7.4;
    int b = 99;

    cout << "a + b = " <<
        MathLibrary::Functions::Add(a, b) << endl;
    cout << "a * b = " <<
        MathLibrary::Functions::Multiply(a, b) << endl;
    cout << "a + (a * b) = " <<
        MathLibrary::Functions::AddMultiply(a, b) << endl;

    return 0;
}
