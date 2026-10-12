package dev.squaddesktop.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import dev.squaddesktop.shared.ApiException;

/** Converte erros em respostas JSON com código estável: {@code {code, message, details}}. */
@RestControllerAdvice
public class ApiErrors {

    private static final Logger LOG = Logger.getLogger(ApiErrors.class.getName());

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        HttpStatus status = switch (e.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID -> HttpStatus.BAD_REQUEST;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
        };
        return body(status, e.code(), e.getMessage(), e.details());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "invalid_body", "O conteúdo enviado não é válido.", Map.of());
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<Map<String, Object>> badParameter(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "invalid_parameter", "Parâmetro inválido ou ausente.", Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> method(HttpRequestMethodNotSupportedException e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "Método não permitido.", Map.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, Object>> mediaType(HttpMediaTypeNotSupportedException e) {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "Envie o conteúdo como JSON.", Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> noResource(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "not_found", "Recurso não encontrado.", Map.of());
    }

    /** Cliente desconectou no meio da resposta (SSE ou download): não há a quem responder. */
    @ExceptionHandler({org.springframework.web.context.request.async.AsyncRequestNotUsableException.class,
            org.springframework.web.context.request.async.AsyncRequestTimeoutException.class})
    void clientGone(Exception e) {
        LOG.log(Level.FINE, "Cliente desconectado", e);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        LOG.log(Level.SEVERE, "Erro inesperado na API", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Erro interno no serviço local. Os detalhes estão no log.", Map.of());
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message, Map<String, Object> details) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("message", message);
        if (details != null && !details.isEmpty()) {
            out.put("details", details);
        }
        return ResponseEntity.status(status).body(out);
    }
}
