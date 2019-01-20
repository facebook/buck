#include "go_asm.h"
#include "textflag.h"
#include "local.h"

// func Sum(xs []int64) int64
TEXT ·Sum(SB),7,$0
    MOVQ    $0, SI       // n
    MOVQ    PLUS(xs,0)(FP), BX // BX = &xs[0]
    MOVQ    PLUS(xs_len,8)(FP), CX // len(xs)
    INCQ    CX           // CX++

start:
    DECQ    CX           // CX--
    JZ done              // jump if CX = 0
    ADDQ    (BX), SI     // n += *BX
    ADDQ    $8, BX       // BX += 8
    JMP start

done:
    MOVQ    SI, ret+24(FP) // return n
    RET
