/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IOpcodes.java,v 1.1.1.1.2.1 2004/07/10 03:34:52 vlad_r Exp $
 */
package com.vladium.jcd.opcodes;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
interface IOpcodes
{
    // public: ................................................................
        
    //  opcode              hex     dec opbytes stackwords wideable    
    int _nop              = 0x00; // 00     0   0
    int _aconst_null      = 0x01; // 01     0   +1
    int _iconst_m1        = 0x02; // 02     0   +1
    int _iconst_0         = 0x03; // 03     0   +1
    int _iconst_1         = 0x04; // 04     0   +1
    int _iconst_2         = 0x05; // 05     0   +1
    int _iconst_3         = 0x06; // 06     0   +1
    int _iconst_4         = 0x07; // 07     0   +1
    int _iconst_5         = 0x08; // 08     0   +1
    int _lconst_0         = 0x09; // 09     0   +2
    int _lconst_1         = 0x0A; // 10     0   +2
    int _fconst_0         = 0x0B; // 11     0   +1
    int _fconst_1         = 0x0C; // 12     0   +1
    int _fconst_2         = 0x0D; // 13     0   +1
    int _dconst_0         = 0x0E; // 14     0   +2
    int _dconst_1         = 0x0F; // 15     0   +2
    int _bipush           = 0x10; // 16     1   +1
    int _sipush           = 0x11; // 17     2   +1
    int _ldc              = 0x12; // 18     1   +1
    int _ldc_w            = 0x13; // 19     2   +1
    int _ldc2_w           = 0x14; // 20     2   +2
    int _iload            = 0x15; // 21     1   +1  true
    int _lload            = 0x16; // 22     1   +2  true
    int _fload            = 0x17; // 23     1   +1  true
    int _dload            = 0x18; // 24     1   +2  true
    int _aload            = 0x19; // 25     1   +1  true  
    int _iload_0          = 0x1A; // 26     0   +1
    int _iload_1          = 0x1B; // 27     0   +1
    int _iload_2          = 0x1C; // 28     0   +1
    int _iload_3          = 0x1D; // 29     0   +1
    int _lload_0          = 0x1E; // 30     0   +2
    int _lload_1          = 0x1F; // 31     0   +2
    int _lload_2          = 0x20; // 32     0   +2
    int _lload_3          = 0x21; // 33     0   +2
    int _fload_0          = 0x22; // 34     0   +1
    int _fload_1          = 0x23; // 35     0   +1
    int _fload_2          = 0x24; // 36     0   +1
    int _fload_3          = 0x25; // 37     0   +1
    int _dload_0          = 0x26; // 38     0   +2
    int _dload_1          = 0x27; // 39     0   +2
    int _dload_2          = 0x28; // 40     0   +2
    int _dload_3          = 0x29; // 41     0   +2
    int _aload_0          = 0x2A; // 42     0   +1
    int _aload_1          = 0x2B; // 43     0   +1
    int _aload_2          = 0x2C; // 44     0   +1
    int _aload_3          = 0x2D; // 45     0   +1
    int _iaload           = 0x2E; // 46     0   -1
    int _laload           = 0x2F; // 47     0   0
    int _faload           = 0x30; // 48     0   -1
    int _daload           = 0x31; // 49     0   0
    int _aaload           = 0x32; // 50     0   -1
    int _baload           = 0x33; // 51     0   -1
    int _caload           = 0x34; // 52     0   -1
    int _saload           = 0x35; // 53     0   -1
    int _istore           = 0x36; // 54     1   -1  true
    int _lstore           = 0x37; // 55     1   -2  true
    int _fstore           = 0x38; // 56     1   -1  true
    int _dstore           = 0x39; // 57     1   -2  true
    int _astore           = 0x3A; // 58     1   -1  true
    int _istore_0         = 0x3B; // 59     0   -1
    int _istore_1         = 0x3C; // 60     0   -1
    int _istore_2         = 0x3D; // 61     0   -1
    int _istore_3         = 0x3E; // 62     0   -1
    int _lstore_0         = 0x3F; // 63     0   -2
    int _lstore_1         = 0x40; // 64     0   -2
    int _lstore_2         = 0x41; // 65     0   -2
    int _lstore_3         = 0x42; // 66     0   -2
    int _fstore_0         = 0x43; // 67     0   -1
    int _fstore_1         = 0x44; // 68     0   -1
    int _fstore_2         = 0x45; // 69     0   -1
    int _fstore_3         = 0x46; // 70     0   -1
    int _dstore_0         = 0x47; // 71     0   -2
    int _dstore_1         = 0x48; // 72     0   -2
    int _dstore_2         = 0x49; // 73     0   -2
    int _dstore_3         = 0x4A; // 74     0   -2
    int _astore_0         = 0x4B; // 75     0   -1
    int _astore_1         = 0x4C; // 76     0   -1
    int _astore_2         = 0x4D; // 77     0   -1
    int _astore_3         = 0x4E; // 78     0   -1
    int _iastore          = 0x4F; // 79     0   -3
    int _lastore          = 0x50; // 80     0   -4
    int _fastore          = 0x51; // 81     0   -3
    int _dastore          = 0x52; // 82     0   -4
    int _aastore          = 0x53; // 83     0   -3
    int _bastore          = 0x54; // 84     0   -3
    int _castore          = 0x55; // 85     0   -3
    int _sastore          = 0x56; // 86     0   -3
    int _pop              = 0x57; // 87     0   -1
    int _pop2             = 0x58; // 88     0   -2
    int _dup              = 0x59; // 89     0   +1
    int _dup_x1           = 0x5A; // 90     0   +1
    int _dup_x2           = 0x5B; // 91     0   +1
    int _dup2             = 0x5C; // 92     0   +2
    int _dup2_x1          = 0x5D; // 93     0   +2
    int _dup2_x2          = 0x5E; // 94     0   +2
    int _swap             = 0x5F; // 95     0   0
    int _iadd             = 0x60; // 96     0   -1
    int _ladd             = 0x61; // 97     0   -2
    int _fadd             = 0x62; // 98     0   -1
    int _dadd             = 0x63; // 99     0   -2
    int _isub             = 0x64; // 100    0   -1
    int _lsub             = 0x65; // 101    0   -2
    int _fsub             = 0x66; // 102    0   -1
    int _dsub             = 0x67; // 103    0   -2
    int _imul             = 0x68; // 104    0   -1
    int _lmul             = 0x69; // 105    0   -2
    int _fmul             = 0x6A; // 106    0   -1
    int _dmul             = 0x6B; // 107    0   -2
    int _idiv             = 0x6C; // 108    0   -1
    int _ldiv             = 0x6D; // 109    0   -2
    int _fdiv             = 0x6E; // 110    0   -1
    int _ddiv             = 0x6F; // 111    0   -2
    int _irem             = 0x70; // 112    0   -1
    int _lrem             = 0x71; // 113    0   -2
    int _frem             = 0x72; // 114    0   -1
    int _drem             = 0x73; // 115    0   -2
    int _ineg             = 0x74; // 116    0   0
    int _lneg             = 0x75; // 117    0   0
    int _fneg             = 0x76; // 118    0   0
    int _dneg             = 0x77; // 119    0   0
    int _ishl             = 0x78; // 120    0   -1
    int _lshl             = 0x79; // 121    0   -1
    int _ishr             = 0x7A; // 122    0   -1
    int _lshr             = 0x7B; // 123    0   -1
    int _iushr            = 0x7C; // 124    0   -1
    int _lushr            = 0x7D; // 125    0   -2
    int _iand             = 0x7E; // 126    0   -1
    int _land             = 0x7F; // 127    0   -2
    int _ior              = 0x80; // 128    0   -1
    int _lor              = 0x81; // 129    0   -2
    int _ixor             = 0x82; // 130    0   -1
    int _lxor             = 0x83; // 131    0   -2
    int _iinc             = 0x84; // 132    2   0   true    [widening is tricky here]
    int _i2l              = 0x85; // 133    0   +1
    int _i2f              = 0x86; // 134    0   0
    int _i2d              = 0x87; // 135    0   +1
    int _l2i              = 0x88; // 136    0   -1
    int _l2f              = 0x89; // 137    0   -1
    int _l2d              = 0x8A; // 138    0   0
    int _f2i              = 0x8B; // 139    0   0
    int _f2l              = 0x8C; // 140    0   +1
    int _f2d              = 0x8D; // 141    0   +1
    int _d2i              = 0x8E; // 142    0   -1
    int _d2l              = 0x8F; // 143    0   0
    int _d2f              = 0x90; // 144    0   -1
    int _i2b              = 0x91; // 145    0   0
    int _i2c              = 0x92; // 146    0   0
    int _i2s              = 0x93; // 147    0   0
    int _lcmp             = 0x94; // 148    0   -3
    int _fcmpl            = 0x95; // 149    0   -1
    int _fcmpg            = 0x96; // 150    0   -1
    int _dcmpl            = 0x97; // 151    0   -3
    int _dcmpg            = 0x98; // 152    0   -3
    int _ifeq             = 0x99; // 153    2   -1
    int _ifne             = 0x9A; // 154    2   -1
    int _iflt             = 0x9B; // 155    2   -1
    int _ifge             = 0x9C; // 156    2   -1
    int _ifgt             = 0x9D; // 157    2   -1
    int _ifle             = 0x9E; // 158    2   -1
    int _if_icmpeq        = 0x9F; // 159    2   -2
    int _if_icmpne        = 0xA0; // 160    2   -2
    int _if_icmplt        = 0xA1; // 161    2   -2
    int _if_icmpge        = 0xA2; // 162    2   -2
    int _if_icmpgt        = 0xA3; // 163    2   -2
    int _if_icmple        = 0xA4; // 164    2   -2
    int _if_acmpeq        = 0xA5; // 165    2   -2
    int _if_acmpne        = 0xA6; // 166    2   -2
    int _goto             = 0xA7; // 167    2   0
    int _jsr              = 0xA8; // 168    2   +1
    int _ret              = 0xA9; // 169    1   0   true
    int _tableswitch      = 0xAA; // 170    *   -1      [there are padding bytes and variable number of operands]
    int _lookupswitch     = 0xAB; // 171    *   -1      [there are padding bytes and variable number of operands]
    int _ireturn          = 0xAC; // 172    0   -1*     [current method returns]
    int _lreturn          = 0xAD; // 173    0   -2*     [current method returns]
    int _freturn          = 0xAE; // 174    0   -1*     [current method returns]
    int _dreturn          = 0xAF; // 175    0   -2*     [current method returns]
    int _areturn          = 0xB0; // 176    0   -1*     [current method returns]
    int _return           = 0xB1; // 177    0   0*      [current method returns]
    int _getstatic        = 0xB2; // 178    2   +1 or +2*   [after stack depends on the field type]
    int _putstatic        = 0xB3; // 179    2   -1 or -2*   [after stack depends on the field type]
    int _getfield         = 0xB4; // 180    2   0 or +1*    [after stack depends on the field type]
    int _putfield         = 0xB5; // 181    2   -2 or -3*   [after stack depends on the field type]
    int _invokevirtual    = 0xB6; // 182    2   *   *   [stack words pushed for the call are emptied]
    int _invokespecial    = 0xB7; // 183    2   *   *   [stack words pushed for the call are emptied]
    int _invokestatic     = 0xB8; // 184    2   *   *   [stack words pushed for the call are emptied]
    int _invokeinterface  = 0xB9; // 185    4   *   *   [last operand is 0; stack words pushed for the call are emptied]
    int _unused           = 0xBA; // 186    *   *   *   [for historical reasons, opcode value 186 is not used]
    int _new              = 0xBB; // 187    2   +1
    int _newarray         = 0xBC; // 188    1   0
    int _anewarray        = 0xBD; // 189    2   0
    int _arraylength      = 0xBE; // 190    0   0
    int _athrow           = 0xBF; // 191    0   0*  *   [stack frame is emptied except for 1 obj ref]
    int _checkcast        = 0xC0; // 192    2   0
    int _instanceof       = 0xC1; // 193    2   0
    int _monitorenter     = 0xC2; // 194    0   -1
    int _monitorexit      = 0xC3; // 195    0   -1
    int _wide             = 0xC4; // 196    *   *       [depends on instruction being modified]
    int _multianewarray   = 0xC5; // 197    3   *       [variable number of stack operands]
    int _ifnull           = 0xC6; // 198    2   -1
    int _ifnonnull        = 0xC7; // 199    2   -1
    int _goto_w           = 0xC8; // 200    4   0
    int _jsr_w            = 0xC9; // 201    4   +1
    // reserved opcodes:
    int _breakpoint       = 0xCA; // 202
    int _impdep1          = 0xFE; // 254
    int _impdep2          = 0xFF; // 255

    
    String [] MNEMONICS =
    {
        "nop",              // 0x00    00
        "aconst_null",      // 0x01    01
        "iconst_m1",        // 0x02    02
        "iconst_0",         // 0x03    03
        "iconst_1",         // 0x04    04
        "iconst_2",         // 0x05    05
        "iconst_3",         // 0x06    06
        "iconst_4",         // 0x07    07
        "iconst_5",         // 0x08    08
        "lconst_0",         // 0x09    09
        "lconst_1",         // 0x0A    10
        "fconst_0",         // 0x0B    11
        "fconst_1",         // 0x0C    12
        "fconst_2",         // 0x0D    13
        "dconst_0",         // 0x0E    14
        "dconst_1",         // 0x0F    15
        "bipush",           // 0x10    16
        "sipush",           // 0x11    17
        "ldc",              // 0x12    18
        "ldc_w",            // 0x13    19
        "ldc2_w",           // 0x14    20
        "iload",            // 0x15    21
        "lload",            // 0x16    22
        "fload",            // 0x17    23
        "dload",            // 0x18    24
        "aload",            // 0x19    25
        "iload_0",          // 0x1A    26
        "iload_1",          // 0x1B    27
        "iload_2",          // 0x1C    28
        "iload_3",          // 0x1D    29
        "lload_0",          // 0x1E    30
        "lload_1",          // 0x1F    31
        "lload_2",          // 0x20    32
        "lload_3",          // 0x21    33
        "fload_0",          // 0x22    34
        "fload_1",          // 0x23    35
        "fload_2",          // 0x24    36
        "fload_3",          // 0x25    37
        "dload_0",          // 0x26    38
        "dload_1",          // 0x27    39
        "dload_2",          // 0x28    40
        "dload_3",          // 0x29    41
        "aload_0",          // 0x2A    42
        "aload_1",          // 0x2B    43
        "aload_2",          // 0x2C    44
        "aload_3",          // 0x2D    45
        "iaload",           // 0x2E    46
        "laload",           // 0x2F    47
        "faload",           // 0x30    48
        "daload",           // 0x31    49
        "aaload",           // 0x32    50
        "baload",           // 0x33    51
        "caload",           // 0x34    52
        "saload",           // 0x35    53
        "istore",           // 0x36    54
        "lstore",           // 0x37    55
        "fstore",           // 0x38    56
        "dstore",           // 0x39    57
        "astore",           // 0x3A    58
        "istore_0",         // 0x3B    59
        "istore_1",         // 0x3C    60
        "istore_2",         // 0x3D    61
        "istore_3",         // 0x3E    62
        "lstore_0",         // 0x3F    63
        "lstore_1",         // 0x40    64
        "lstore_2",         // 0x41    65
        "lstore_3",         // 0x42    66
        "fstore_0",         // 0x43    67
        "fstore_1",         // 0x44    68
        "fstore_2",         // 0x45    69
        "fstore_3",         // 0x46    70
        "dstore_0",         // 0x47    71
        "dstore_1",         // 0x48    72
        "dstore_2",         // 0x49    73
        "dstore_3",         // 0x4A    74
        "astore_0",         // 0x4B    75
        "astore_1",         // 0x4C    76
        "astore_2",         // 0x4D    77
        "astore_3",         // 0x4E    78
        "iastore",          // 0x4F    79
        "lastore",          // 0x50    80
        "fastore",          // 0x51    81
        "dastore",          // 0x52    82
        "aastore",          // 0x53    83
        "bastore",          // 0x54    84
        "castore",          // 0x55    85
        "sastore",          // 0x56    86
        "pop",              // 0x57    87
        "pop2",             // 0x58    88
        "dup",              // 0x59    089
        "dup_x1",           // 0x5A    090
        "dup_x2",           // 0x5B    091
        "dup2",             // 0x5C    092
        "dup2_x1",          // 0x5D    093
        "dup2_x2",          // 0x5E    094
        "swap",             // 0x5F    095
        "iadd",             // 0x60    096
        "ladd",             // 0x61    097
        "fadd",             // 0x62    098
        "dadd",             // 0x63    099
        "isub",             // 0x64    100
        "lsub",             // 0x65    101
        "fsub",             // 0x66    102
        "dsub",             // 0x67    103
        "imul",             // 0x68    104
        "lmul",             // 0x69    105
        "fmul",             // 0x6A    106
        "dmul",             // 0x6B    107
        "idiv",             // 0x6C    108
        "ldiv",             // 0x6D    109
        "fdiv",             // 0x6E    110
        "ddiv",             // 0x6F    111
        "irem",             // 0x70    112
        "lrem",             // 0x71    113
        "frem",             // 0x72    114
        "drem",             // 0x73    115
        "ineg",             // 0x74    116
        "lneg",             // 0x75    117
        "fneg",             // 0x76    118
        "dneg",             // 0x77    119
        "ishl",             // 0x78    120
        "lshl",             // 0x79    121
        "ishr",             // 0x7A    122
        "lshr",             // 0x7B    123
        "iushr",            // 0x7C    124
        "lushr",            // 0x7D    125
        "iand",             // 0x7E    126
        "land",             // 0x7F    127
        "ior",              // 0x80    128
        "lor",              // 0x81    129
        "ixor",             // 0x82    130
        "lxor",             // 0x83    131
        "iinc",             // 0x84    132
        "i2l",              // 0x85    133
        "i2f",              // 0x86    134
        "i2d",              // 0x87    135
        "l2i",              // 0x88    136
        "l2f",              // 0x89    137
        "l2d",              // 0x8A    138
        "f2i",              // 0x8B    139
        "f2l",              // 0x8C    140
        "f2d",              // 0x8D    141
        "d2i",              // 0x8E    142
        "d2l",              // 0x8F    143
        "d2f",              // 0x90    144
        "i2b",              // 0x91    145
        "i2c",              // 0x92    146
        "i2s",              // 0x93    147
        "lcmp",             // 0x94    148
        "fcmpl",            // 0x95    149
        "fcmpg",            // 0x96    150
        "dcmpl",            // 0x97    151
        "dcmpg",            // 0x98    152
        "ifeq",             // 0x99    153
        "ifne",             // 0x9A    154
        "iflt",             // 0x9B    155
        "ifge",             // 0x9C    156
        "ifgt",             // 0x9D    157
        "ifle",             // 0x9E    158
        "if_icmpeq",        // 0x9F    159
        "if_icmpne",        // 0xA0    160
        "if_icmplt",        // 0xA1    161
        "if_icmpge",        // 0xA2    162
        "if_icmpgt",        // 0xA3    163
        "if_icmple",        // 0xA4    164
        "if_acmpeq",        // 0xA5    165
        "if_acmpne",        // 0xA6    166
        "goto",             // 0xA7    167
        "jsr",              // 0xA8    168
        "ret",              // 0xA9    169
        "tableswitch",      // 0xAA    170
        "lookupswitch",     // 0xAB    171
        "ireturn",          // 0xAC    172
        "lreturn",          // 0xAD    173
        "freturn",          // 0xAE    174
        "dreturn",          // 0xAF    175
        "areturn",          // 0xB0    176
        "return",           // 0xB1    177
        "getstatic",        // 0xB2    178
        "putstatic",        // 0xB3    179
        "getfield",         // 0xB4    180
        "putfield",         // 0xB5    181
        "invokevirtual",    // 0xB6    182
        "invokespecial",    // 0xB7    183
        "invokestatic",     // 0xB8    184
        "invokeinterface",  // 0xB9    185
        "unused",           // 0xBA    186
        "new",              // 0xBB    187
        "newarray",         // 0xBC    188
        "anewarray",        // 0xBD    189
        "arraylength",      // 0xBE    190
        "athrow",           // 0xBF    191
        "checkcast",        // 0xC0    192
        "instanceof",       // 0xC1    193
        "monitorenter",     // 0xC2    194
        "monitorexit",      // 0xC3    195
        "[wide]",           // 0xC4    196
        "multianewarray",   // 0xC5    197
        "ifnull",           // 0xC6    198
        "ifnonnull",        // 0xC7    199
        "goto_w",           // 0xC8    200
        "jsr_w"             // 0xC9    201
    };
    
    
    boolean [] CONDITIONAL_BRANCHES = clinit._CONDITIONAL_BRANCHES;
    boolean [] COMPOUND_CONDITIONAL_BRANCHES = clinit._COMPOUND_CONDITIONAL_BRANCHES;
    boolean [] UNCONDITIONAL_BRANCHES = clinit._UNCONDITIONAL_BRANCHES;
    boolean [] BRANCHES = clinit._BRANCHES;
    
    int [] NARROW_SIZE = clinit._NARROW_SIZE; // including the opcode itself
    int [] WIDE_SIZE = clinit._WIDE_SIZE; // including the opcode itself
    
    
    static final class clinit
    {
        static final boolean [] _CONDITIONAL_BRANCHES;
        static final boolean [] _COMPOUND_CONDITIONAL_BRANCHES;
        static final boolean [] _UNCONDITIONAL_BRANCHES;
        static final boolean [] _BRANCHES;
        static final int [] _NARROW_SIZE;
        static final int [] _WIDE_SIZE;
        
        static
        {
            final int opcodeCount = MNEMONICS.length;
            
            _CONDITIONAL_BRANCHES = new boolean [opcodeCount];
            
            _CONDITIONAL_BRANCHES [_ifeq] = true;
            _CONDITIONAL_BRANCHES [_iflt] = true;
            _CONDITIONAL_BRANCHES [_ifle] = true;
            _CONDITIONAL_BRANCHES [_ifne] = true;
            _CONDITIONAL_BRANCHES [_ifgt] = true;
            _CONDITIONAL_BRANCHES [_ifge] = true;
            _CONDITIONAL_BRANCHES [_ifnull] = true;
            _CONDITIONAL_BRANCHES [_ifnonnull] = true;
            _CONDITIONAL_BRANCHES [_if_icmpeq] = true;
            _CONDITIONAL_BRANCHES [_if_icmpne] = true;
            _CONDITIONAL_BRANCHES [_if_icmplt] = true;
            _CONDITIONAL_BRANCHES [_if_icmpgt] = true;
            _CONDITIONAL_BRANCHES [_if_icmple] = true;
            _CONDITIONAL_BRANCHES [_if_icmpge] = true;
            _CONDITIONAL_BRANCHES [_if_acmpeq] = true;
            _CONDITIONAL_BRANCHES [_if_acmpne] = true;


            _COMPOUND_CONDITIONAL_BRANCHES = new boolean [opcodeCount];
            
            _COMPOUND_CONDITIONAL_BRANCHES [_tableswitch] = true;
            _COMPOUND_CONDITIONAL_BRANCHES [_lookupswitch] = true;

            
            _UNCONDITIONAL_BRANCHES = new boolean  [opcodeCount];
            
            _UNCONDITIONAL_BRANCHES [_goto] = true;
            _UNCONDITIONAL_BRANCHES [_goto_w] = true;
            _UNCONDITIONAL_BRANCHES [_jsr] = true;
            _UNCONDITIONAL_BRANCHES [_jsr_w] = true;
            _UNCONDITIONAL_BRANCHES [_ret] = true;

            _UNCONDITIONAL_BRANCHES [_ireturn] = true;
            _UNCONDITIONAL_BRANCHES [_lreturn] = true;
            _UNCONDITIONAL_BRANCHES [_freturn] = true;
            _UNCONDITIONAL_BRANCHES [_dreturn] = true;
            _UNCONDITIONAL_BRANCHES [_areturn] = true;
            _UNCONDITIONAL_BRANCHES [_return] = true;

            _UNCONDITIONAL_BRANCHES [_athrow] = true;


            _BRANCHES = new boolean [opcodeCount];
            
            for (int o = 0; o < opcodeCount; ++ o)
                if (_CONDITIONAL_BRANCHES [o]) _BRANCHES [o] = true;

            for (int o = 0; o < opcodeCount; ++ o)
                if (_COMPOUND_CONDITIONAL_BRANCHES [o]) _BRANCHES [o] = true;

            for (int o = 0; o < opcodeCount; ++ o)
                if (_UNCONDITIONAL_BRANCHES [o]) _BRANCHES [o] = true;


            _NARROW_SIZE = new int [opcodeCount];
            
            for (int o = 0; o < opcodeCount; ++ o) _NARROW_SIZE [o] = 1;
            
            _NARROW_SIZE [_bipush] = 2;
            _NARROW_SIZE [_sipush] = 3;

            _NARROW_SIZE [_ldc] = 2;
            _NARROW_SIZE [_ldc_w] = 3;
            _NARROW_SIZE [_ldc2_w] = 3;

            _NARROW_SIZE [_iload] = 2;
            _NARROW_SIZE [_lload] = 2;
            _NARROW_SIZE [_fload] = 2;
            _NARROW_SIZE [_dload] = 2;
            _NARROW_SIZE [_aload] = 2;
            _NARROW_SIZE [_istore] = 2;
            _NARROW_SIZE [_lstore] = 2;
            _NARROW_SIZE [_fstore] = 2;
            _NARROW_SIZE [_dstore] = 2;
            _NARROW_SIZE [_astore] = 2;

            _NARROW_SIZE [_iinc] = 3;

            _NARROW_SIZE [_ifeq] = 3;
            _NARROW_SIZE [_ifne] = 3;
            _NARROW_SIZE [_iflt] = 3;
            _NARROW_SIZE [_ifge] = 3;
            _NARROW_SIZE [_ifgt] = 3;
            _NARROW_SIZE [_ifle] = 3;
            _NARROW_SIZE [_if_icmpeq] = 3;
            _NARROW_SIZE [_if_icmpne] = 3;
            _NARROW_SIZE [_if_icmplt] = 3;
            _NARROW_SIZE [_if_icmpge] = 3;
            _NARROW_SIZE [_if_icmpgt] = 3;
            _NARROW_SIZE [_if_icmple] = 3;
            _NARROW_SIZE [_if_acmpeq] = 3;
            _NARROW_SIZE [_if_acmpne] = 3;
            _NARROW_SIZE [_goto] = 3;
            _NARROW_SIZE [_jsr] = 3;
            _NARROW_SIZE [_ifnull] = 3;
            _NARROW_SIZE [_ifnonnull] = 3;

            _NARROW_SIZE [_ret] = 2;

            _NARROW_SIZE [_lookupswitch] = -1;   // special case #2
            _NARROW_SIZE [_tableswitch] = 0;    // special case #1
            
            _NARROW_SIZE [_getstatic] = 3;
            _NARROW_SIZE [_putstatic] = 3;
            _NARROW_SIZE [_getfield] = 3;
            _NARROW_SIZE [_putfield] = 3;

            _NARROW_SIZE [_invokevirtual] = 3;
            _NARROW_SIZE [_invokespecial] = 3;
            _NARROW_SIZE [_invokestatic] = 3;

            _NARROW_SIZE [_invokeinterface] = 5;
                
            _NARROW_SIZE [_new] = 3;
            _NARROW_SIZE [_checkcast] = 3;
            _NARROW_SIZE [_instanceof] = 3;

            _NARROW_SIZE [_newarray] = 2;
            _NARROW_SIZE [_anewarray] = 3;
            _NARROW_SIZE [_multianewarray] = 4;

            _NARROW_SIZE [_goto_w] = 5;
            _NARROW_SIZE [_jsr_w] = 5;
            
            
            _WIDE_SIZE = (int []) _NARROW_SIZE.clone ();
            
            _WIDE_SIZE [_iload] = 3;
            _WIDE_SIZE [_lload] = 3;
            _WIDE_SIZE [_fload] = 3;
            _WIDE_SIZE [_dload] = 3;
            _WIDE_SIZE [_aload] = 3;
            _WIDE_SIZE [_istore] = 3;
            _WIDE_SIZE [_lstore] = 3;
            _WIDE_SIZE [_fstore] = 3;
            _WIDE_SIZE [_dstore] = 3;
            _WIDE_SIZE [_astore] = 3;
            
            _WIDE_SIZE [_iinc] = 5;
            
            _WIDE_SIZE [_ret] = 3;
        }
        
    } // end of nested class
    
} // end of interface
// ----------------------------------------------------------------------------

