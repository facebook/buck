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

extern "C" {

MATHLIBRARY_API double MathLibrary_Add(double a, double b);
MATHLIBRARY_API double MathLibrary_Multiply(double a, double b);
MATHLIBRARY_API double MathLibrary_AddMultiply(double a, double b);

}