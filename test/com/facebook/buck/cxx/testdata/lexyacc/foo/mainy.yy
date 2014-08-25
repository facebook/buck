%{
#include <stdio.h>
extern int yylex(void);
extern void yyerror(const char *);
%}
%token NAME NUMBER
%%
statement: NAME '=' expression
          | expression { printf("= %d\n", $1); }
 ;
expression: expression '+' NUMBER { $$ = $1 + $3; }
           | expression '-' NUMBER { $$ = $1 - $3; }
 | NUMBER { $$ = $1; }
 ;
%%
