#include <stdio.h>
#include <windows.h>

BOOL Is64BitWindows()
{
#if defined(_WIN64)
 return TRUE;
#elif defined(_WIN32)
 BOOL f64 = FALSE;
 return IsWow64Process(GetCurrentProcess(), &f64) && f64;
#else
 return FALSE;
#endif
}

BOOL Is64Wow()
{
 BOOL f64 = FALSE;
 return IsWow64Process(GetCurrentProcess(), &f64) && f64;
}

int main(void)
{
  TCHAR buffer[MAX_PATH]={0};
  DWORD bufSize=sizeof(buffer)/sizeof(*buffer);
  // Get the fully-qualified path of the executable
  GetModuleFileName(NULL, buffer, bufSize);
  printf("executable is %s\n", buffer);

  if(Is64BitWindows())
    printf("The process is 64bits\n");
  else
    printf("The process is 32bits\n");

  if(Is64Wow())
    printf("The process is WOW64\n");
  return 0;
}
