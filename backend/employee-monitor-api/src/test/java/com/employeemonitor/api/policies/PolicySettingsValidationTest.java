package com.employeemonitor.api.policies;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PolicySettingsValidationTest {

    private static jakarta.validation.ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    static PolicySettings valid(int screenshotInterval, String tz, String start, String end) {
        return new PolicySettings(true, 300, true, false, true, true, true, false, screenshotInterval,
                false, 30, 30, 300, start, end, EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), tz, 90, 30);
    }

    private static Set<String> violations(PolicySettings settings) {
        return validator.validate(settings).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    @Test
    void defaultsAreValid() {
        assertThat(violations(valid(900, "Asia/Kolkata", "09:00", "18:00"))).isEmpty();
    }

    @Test
    void screenshotIntervalMustBeAnAllowedValue() {
        assertThat(violations(valid(123, "UTC", "09:00", "18:00")))
                .anyMatch(m -> m.contains("screenshotIntervalSeconds"));
    }

    @Test
    void timezoneMustExist() {
        assertThat(violations(valid(900, "Nowhere/City", "09:00", "18:00")))
                .anyMatch(m -> m.contains("timezone"));
    }

    @Test
    void windowMustBeOrderedAndWellFormed() {
        assertThat(violations(valid(900, "UTC", "18:00", "09:00")))
                .anyMatch(m -> m.contains("monitoringEnd"));
        assertThat(violations(valid(900, "UTC", "9am", "18:00")))
                .anyMatch(m -> m.contains("HH:mm"));
    }
}
