package dev.chaya.api.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An error this operation can answer with, beyond the ones every operation has (OpenApiConfig adds those): an RFC 9457
 * problem with the given status. The description names the stable codes. Used instead of springdoc's @ApiResponse,
 * which would drop the generated success response and give the error the success body's schema.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(ProblemResponse.List.class)
public @interface ProblemResponse {

    int status();

    String description();

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {
        ProblemResponse[] value();
    }
}
