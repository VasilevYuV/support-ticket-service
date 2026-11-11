package ru.vasilev.support.ticket.validation;

import static org.assertj.core.api.Assertions.*;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.vasilev.support.common.error.ApiException;
import ru.vasilev.support.ticket.dto.CreateTicketRequest;
import ru.vasilev.support.ticket.dto.ReplaceTicketDetailsRequest;
import ru.vasilev.support.ticket.entity.TicketPriority;

class TicketRequestValidatorTest {
    private final TicketRequestValidator validator = new TicketRequestValidator();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\r\n", "\u00a0\u2003\u202f\u3000"})
    void rejectsMissingOrWhitespaceTitle(String title) {
        assertValidationError(new CreateTicketRequest(title, 1L, null, null, null), "title");
    }

    @ParameterizedTest
    @MethodSource("validTitles")
    void acceptsTitleBoundariesInCodePoints(String title) {
        assertThatCode(() ->
                        validator.validate(new CreateTicketRequest(title, 1L, null, null, null)))
                .doesNotThrowAnyException();
    }

    static Stream<String> validTitles() {
        return Stream.of("Я", "Я".repeat(200), "😀".repeat(200), " " + "Я".repeat(198) + " ");
    }

    @ParameterizedTest
    @MethodSource("longTitles")
    void rejectsLongTitleBeforeWhitespaceTrimming(String title) {
        assertValidationError(new CreateTicketRequest(title, 1L, null, null, null), "title");
    }

    static Stream<String> longTitles() {
        return Stream.of("Я".repeat(201), "😀".repeat(201), " " + "Я".repeat(199) + " ");
    }

    @ParameterizedTest
    @MethodSource("validDescriptions")
    void acceptsDescriptionBoundariesForCreationAndReplacement(String description) {
        assertThatCode(() ->
                        validator.validate(
                                new CreateTicketRequest("Заявка", 1L, description, null, null)))
                .doesNotThrowAnyException();
        assertThatCode(() ->
                        validator.validate(
                                new ReplaceTicketDetailsRequest(
                                        description, TicketPriority.LOW, null)))
                .doesNotThrowAnyException();
    }

    static Stream<String> validDescriptions() {
        return Stream.of("", "Я".repeat(10000), "😀".repeat(10000));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Я", "😀"})
    void rejectsLongDescriptionForCreationAndReplacement(String character) {
        var description = character.repeat(10001);
        assertValidationError(
                new CreateTicketRequest("Заявка", 1L, description, null, null), "description");
        assertThatThrownBy(() ->
                        validator.validate(
                                new ReplaceTicketDetailsRequest(
                                        description, TicketPriority.LOW, null)))
                .isInstanceOfSatisfying(
                        ApiException.class, error -> assertField(error, "description"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsMissingOrNonPositiveCategory(Long categoryId) {
        assertValidationError(
                new CreateTicketRequest("Заявка", categoryId, null, null, null), "categoryId");
    }

    private void assertValidationError(CreateTicketRequest request, String field) {
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOfSatisfying(ApiException.class, error -> assertField(error, field));
    }

    private void assertField(ApiException error, String field) {
        assertThat(error.getStatus().value()).isEqualTo(400);
        assertThat(error.getCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.getViolations())
                .singleElement()
                .satisfies(violation -> {
                    assertThat(violation.field()).isEqualTo(field);
                    assertThat(violation.message()).isNotBlank();
                });
    }
}
