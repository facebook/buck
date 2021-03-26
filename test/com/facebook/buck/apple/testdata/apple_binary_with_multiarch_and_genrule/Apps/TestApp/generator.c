#include <stdio.h>

int main() {
    printf("#include <CoreFoundation/CoreFoundation.h>\n");
    printf("\n");
    printf("int main(int argc, char *argv[]) {\n");
    printf("  return CFStringGetLength(CFSTR(\"Hello World!\"));\n");
    printf("}\n");
    return 0;
}
