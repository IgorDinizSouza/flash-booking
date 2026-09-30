package com.flashbooking.model.dto.request;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Nome de evento: sem caracteres de controle (inclui NUL, que o PostgreSQL rejeita) nem surrogates soltos, e com
 * no maximo {@value #MAX_LENGTH} pontos de codigo Unicode DEPOIS do trim (o que e gravado em VARCHAR(150)).
 * Nulo e branco ficam a cargo de {@code @NotBlank}.
 */
@Documented
@Constraint(validatedBy = EventNameValidator.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidEventName {

    int MAX_LENGTH = 150;

    String message() default "Event name must have at most 150 characters (Unicode code points, after trim) "
            + "and no control characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
