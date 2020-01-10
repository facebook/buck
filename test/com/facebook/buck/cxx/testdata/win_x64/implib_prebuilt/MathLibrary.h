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
// MathLibrary.h - Contains declaration of Function class
#pragma once

#ifdef MATHLIBRARY_EXPORTS
#define MATHLIBRARY_API __declspec(dllexport)
#else
#define MATHLIBRARY_API __declspec(dllimport)
#endif

namespace MathLibrary
{
    // This class is exported from the MathLibrary.dll
    class Functions
    {
    public:
        // Returns a + b
        static MATHLIBRARY_API double Add(double a, double b);

        // Returns a * b
        static MATHLIBRARY_API double Multiply(double a, double b);

        // Returns a + (a * b)
        static MATHLIBRARY_API double AddMultiply(double a, double b);
    };
}
