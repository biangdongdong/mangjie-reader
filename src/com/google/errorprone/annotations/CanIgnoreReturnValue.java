/* 空注解，代替 errorprone 的同名注解 */
package com.google.errorprone.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.CLASS)
public @interface CanIgnoreReturnValue {}
