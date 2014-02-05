package testdata;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Annotated.Marker(a = "on class", b = {"A", "B", "C" },
        c = @Annotated.Nested(e="E1", f=1695938256, g=7264081114510713000L),
        d = { @Annotated.Nested(e="E2", f=1695938256, g=7264081114510713000L) })
public class Annotated {

    @Annotated.Marker(a="on field")
    public String field;

    @Annotated.Marker(a="on method")
    public void method(String a, @Annotated.Marker(a="on parameter") String b) {}

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Marker {
        String a() default "";
        String[] b() default {};
        Nested c() default @Nested;
        Nested[] d() default {};
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Nested {
        String e() default "";
        int f() default 0;
        long g() default 0L;
    }
}
