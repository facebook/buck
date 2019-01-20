.code
add_by_ref PROC
movsxd rax, ecx
movsxd rdx, edx
add    rax, rdx ; result in RAX
mov qword ptr [r8], rax ; store / deref
ret
add_by_ref ENDP
END
