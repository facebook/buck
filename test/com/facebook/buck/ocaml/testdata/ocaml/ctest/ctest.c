#include <stdio.h>
#include <caml/mlvalues.h>

value plus_native (value x1,value x2,value x3,value x4,value x5,value x6)
{
    printf("<< NATIVE PLUS >>\n");
    fflush(stdout);
    return Val_long(
        Long_val(x1) + Long_val(x2)
        + Long_val(x3) + Long_val(x4)
        + Long_val(x5) + Long_val(x6));
}

value plus_bytecode (value * tab_val, int num_val)
{
    int i;
    long res;
    printf("<< BYTECODED PLUS >>:");
    fflush(stdout);
    for (i=0,res=0;i<num_val;i++)
        res += Long_val(tab_val[i]) ;
    return Val_long(res);
}
