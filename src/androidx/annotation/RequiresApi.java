/* 空注解，代替 androidx.annotation.RequiresApi，只为让移植代码编过 */
package androidx.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface RequiresApi {
    int value() default 1;
    int api() default 1;
}
