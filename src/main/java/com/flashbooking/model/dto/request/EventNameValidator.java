package com.flashbooking.model.dto.request;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class EventNameValidator implements ConstraintValidator<ValidEventName, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        // controle e surrogates soltos sao checados no texto BRUTO: trim() removeria NUL/controles das bordas
        boolean clean = value.codePoints()
                .noneMatch(cp -> Character.isISOControl(cp) || Character.getType(cp) == Character.SURROGATE);
        return clean && value.trim().codePointCount(0, value.trim().length()) <= ValidEventName.MAX_LENGTH;
    }
}
