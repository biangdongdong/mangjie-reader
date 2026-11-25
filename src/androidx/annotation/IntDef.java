/* 空注解，代替 androidx.annotation.IntDef */
package androidx.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.CLASS)
public @interface IntDef {
    int[] value() default {};
    boolean flag() default false;
    boolean open() default false;
}
