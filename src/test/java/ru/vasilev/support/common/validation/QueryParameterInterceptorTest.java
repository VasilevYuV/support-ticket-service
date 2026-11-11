package ru.vasilev.support.common.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import ru.vasilev.support.common.error.ApiException;

class QueryParameterInterceptorTest {
    private final QueryParameterInterceptor interceptor = new QueryParameterInterceptor();

    @Test
    void acceptsDeclaredSingleParameter() throws Exception {
        var request = new MockHttpServletRequest();
        request.setQueryString("page=1");
        request.addParameter("page", "1");
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), handler()))
                .isTrue();
    }

    @ParameterizedTest
    @CsvSource({"page,empty", "page,repeated", "unknown,single"})
    void rejectsEmptyRepeatedAndUnknownParameters(String name, String variant) throws Exception {
        var request = new MockHttpServletRequest();
        request.setQueryString(name + "=1");
        request.addParameter(name, variant.equals("empty") ? "" : "1");
        if (variant.equals("repeated")) {
            request.addParameter(name, "2");
        }
        var handler = handler();
        assertThatThrownBy(() ->
                        interceptor.preHandle(request, new MockHttpServletResponse(), handler))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> {
                            assertThat(exception.getCode()).isEqualTo("INVALID_REQUEST");
                            assertThat(exception.getViolations().getFirst().field())
                                    .isEqualTo(name);
                        });
    }

    private HandlerMethod handler() throws Exception {
        return new HandlerMethod(new Endpoint(), Endpoint.class.getMethod("list", String.class));
    }

    public static class Endpoint {
        public void list(@RequestParam("page") String page) {}
    }
}
