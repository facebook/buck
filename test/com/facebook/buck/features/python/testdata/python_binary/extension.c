#if defined(PLATFORM_A) && !defined(PLATFORM_B)
#define platform platform_a
#elif !defined(PLATFORM_A) && defined(PLATFORM_B)
#define platform platform_b
#else
#error Expected exactly one platform
#endif

extern int platform();

int foo() {
  return platform();
}
