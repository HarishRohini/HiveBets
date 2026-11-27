package dev.hivebets.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Shared MVC wiring: {@link CurrentUser} parameters and JSON error bodies. Import it in a servlet service. */
@Configuration(proxyBeanMethods = false)
public class WebSupport {

    @Bean
    WebMvcConfigurer currentUserResolver() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter p) {
                        return p.nestedIfOptional().getNestedParameterType() == CurrentUser.class;
                    }

                    @Override
                    public Object resolveArgument(MethodParameter p, ModelAndViewContainer m, NativeWebRequest req,
                                                  WebDataBinderFactory f) {
                        HttpServletRequest r = req.getNativeRequest(HttpServletRequest.class);
                        CurrentUser u = CurrentUser.fromHeaders(r.getHeader(CurrentUser.H_ID), r.getHeader(CurrentUser.H_NAME),
                                r.getHeader(CurrentUser.H_ROLES), r.getHeader(CurrentUser.H_TYPE));
                        if (p.getParameterType() == java.util.Optional.class) return java.util.Optional.ofNullable(u);
                        if (u == null && !p.isOptional()) throw ApiException.unauthenticated();
                        return u;
                    }
                });
            }
        };
    }

    @Bean
    ErrorAdvice errorAdvice() {
        return new ErrorAdvice();
    }

    @RestControllerAdvice
    public static class ErrorAdvice {
        private static final Logger log = LoggerFactory.getLogger(ErrorAdvice.class);

        @ExceptionHandler(ApiException.class)
        ResponseEntity<Map<String, String>> api(ApiException e) {
            return ResponseEntity.status(e.status()).body(Map.of("error", e.code(), "message", e.getMessage()));
        }

        @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
                MethodArgumentTypeMismatchException.class, MissingRequestHeaderException.class, IllegalArgumentException.class})
        ResponseEntity<Map<String, String>> bad(Exception e) {
            String msg = e instanceof MethodArgumentNotValidException v && v.getBindingResult().getFieldError() != null
                    ? v.getBindingResult().getFieldError().getField() + ": " + v.getBindingResult().getFieldError().getDefaultMessage()
                    : "The request was not valid.";
            return ResponseEntity.badRequest().body(Map.of("error", "VALIDATION", "message", msg));
        }

        @ExceptionHandler(Exception.class)
        ResponseEntity<Map<String, String>> other(Exception e) {
            if (e instanceof org.springframework.web.servlet.resource.NoResourceFoundException) {
                return ResponseEntity.status(404).body(Map.of("error", "NOT_FOUND", "message", "No such endpoint."));
            }
            log.error("unhandled error", e);
            return ResponseEntity.status(500).body(Map.of("error", "INTERNAL", "message", "Something went wrong."));
        }
    }
}
