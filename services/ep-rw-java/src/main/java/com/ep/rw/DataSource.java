package com.ep.rw;
import java.lang.annotation.*;
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DataSource {
    DataSourceType value() default DataSourceType.WRITE;
    /** 强制走 Primary，防读后写不一致 */
    boolean consistent() default false;
    /** 路由到 TiFlash 列存（OLAP） */
    boolean tiflash() default false;
}
