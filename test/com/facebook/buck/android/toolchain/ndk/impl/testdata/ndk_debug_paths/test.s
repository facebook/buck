  .global _start

  .text
_start:
#if defined(__arm__)
  svc #0
#elif defined(__x86_64__)
  syscall
#endif
