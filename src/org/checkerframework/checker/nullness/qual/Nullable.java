/* 空注解，代替 checkerframework 的同名注解 */
package org.checkerframework.checker.nullness.qual;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.CLASS)
public @interface Nullable {}
