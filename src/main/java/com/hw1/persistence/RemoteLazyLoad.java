package com.hw1.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Add the RemoteLazyLoad annotation definition here
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface RemoteLazyLoad {
}