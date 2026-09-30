package com.acentra.catchy.telemetry.web;

import com.acentra.catchy.telemetry.config.Times;
import com.acentra.catchy.telemetry.dto.OpsDtos.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Writes the contract error shape from places that run outside Spring MVC (filters, security handlers). */
@Component
public class RestErrorWriter {

    private final ObjectMapper mapper;
    private final Clock clock;

    public RestErrorWriter(ObjectMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public ErrorResponse body(HttpServletRequest request, HttpStatus status, String message, List<String> details) {
        return new ErrorResponse(Times.now(clock), status.value(), status.getReasonPhrase(), message,
                request.getRequestURI(), details == null ? List.of() : details);
    }

    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        write(request, response, status, message, List.of());
    }

    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String message,
                      List<String> details) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(), body(request, status, message, details));
    }
}
