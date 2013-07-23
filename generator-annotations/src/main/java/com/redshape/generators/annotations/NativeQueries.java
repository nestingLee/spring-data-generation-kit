package com.redshape.generators.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/**
 * @author Cyril A. Karpenko <self@nikelin.ru>
 */
@Target(ElementType.TYPE)
public @interface NativeQueries {

    public NativeQuery[] value();

}
