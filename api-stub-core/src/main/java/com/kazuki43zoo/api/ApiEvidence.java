/*
 *    Copyright 2016-2017 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.kazuki43zoo.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kazuki43zoo.config.ApiStubProperties;
import lombok.Data;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.StreamUtils;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

public class ApiEvidence {

    private static final DateTimeFormatter DIR_NAME_DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("uuuuMMddHHmmssSSS");
    private static ObjectMapper objectMapperForLog =
            Jackson2ObjectMapperBuilder.json().indentOutput(false).build();
    private static ObjectMapper objectMapperForFile =
            Jackson2ObjectMapperBuilder.json().indentOutput(true).build();

    private final Path dir;
    private final Logger logger;
    private final String contentExtension;
    private final ApiStubProperties properties;
    @Getter
    private final String correlationId;

    public ApiEvidence(ApiStubProperties properties, String method, String path, String dataKey, String correlationId, String contentExtension) {
        MDC.put(properties.getCorrelationIdKey(), correlationId);
        Optional<String> nullableDataKey = Optional.ofNullable(dataKey);
        this.dir = Paths.get(properties.getEvidence().getDir(),
                path, nullableDataKey.orElse(""), method, LocalDateTime.now().format(DIR_NAME_DATE_TIME_FORMAT) + "_" + correlationId);
        this.logger = LoggerFactory.getLogger(method + " " + path +  nullableDataKey.map(key -> " dataKey=" + key).orElse(""));
        this.contentExtension = contentExtension;
        this.properties = properties;
        this.correlationId = correlationId;
    }

    public void start() {
        info("Start.");
        if (properties.getEvidence().isDisabledRequest() && properties.getEvidence().isDisabledUpload()) {
            return;
        }
        if (!dir.toFile().exists() && !this.dir.toFile().mkdirs()) {
            error("Evidence Directory cannot create. dir = {}", dir.toAbsolutePath().toString());
        }
        info("Evidence Dir : {}", dir.toAbsolutePath().toString());
    }

    public void request(HttpServletRequest request, RequestEntity<String> requestEntity) throws IOException, ServletException {
        final EvidenceRequest evidenceRequest = new EvidenceRequest(request.getParameterMap(), requestEntity.getHeaders());
        info("Request      : {}", objectMapperForLog.writeValueAsString(evidenceRequest));
        if (!properties.getEvidence().isDisabledRequest()) {
            try (OutputStream out = new BufferedOutputStream
                    (new FileOutputStream(new File(dir.toFile(), "request.json")))) {
                objectMapperForFile.writeValue(out, evidenceRequest);
            }
        }

        if (requestEntity.getBody() != null) {
            final String body = requestEntity.getBody();
            info("Request body : {}", body);
            if (!properties.getEvidence().isDisabledRequest()) {
                try (OutputStream out = new BufferedOutputStream(
                        new FileOutputStream(new File(dir.toFile(), "body." + contentExtension)))) {
                    Charset charset = Optional.ofNullable(requestEntity.getHeaders().getContentType())
                            .map(MediaType::getCharset)
                            .orElse(StandardCharsets.UTF_8);
                    StreamUtils.copy(body, charset, out);
                }
            }
        } else {
            info("Request body : empty");
        }

        if (request instanceof MultipartHttpServletRequest) {
            int index = 1;
            for (Part part : request.getParts()) {
                String fileName = Paths.get(part.getSubmittedFileName()).getFileName().toString();
                String saveFileName = String.format("uploadFile_%02d_%s", index, fileName);
                File saveFile = new File(dir.toFile(), saveFileName);
                UploadFile uploadFile = new UploadFile(part, saveFileName);
                info("Upload file  : {}", objectMapperForLog.writeValueAsString(uploadFile));
                if (!properties.getEvidence().isDisabledUpload()) {
                    try (InputStream in = part.getInputStream();
                         OutputStream out = new BufferedOutputStream(new FileOutputStream(saveFile))) {
                        FileCopyUtils.copy(in, out);
                    }
                }
                index++;
            }
        }
    }

    public void response(ResponseEntity<?> responseEntity) throws JsonProcessingException {
        EvidenceResponse evidenceResponse = new EvidenceResponse(responseEntity.getStatusCode(), responseEntity.getHeaders());
        info("Response     : {}", objectMapperForLog.writeValueAsString(evidenceResponse));
    }

    public void end() {
        info("End.");
        MDC.clear();
    }

    public void error(String format, Object... args) {
        logger.error(format, args);
    }

    public void error(String format, Throwable t) {
        logger.error(format, t);
    }

    public void warn(String format, Object... args) {
        logger.warn(format, args);
    }

    public void warn(String format, Throwable t) {
        logger.warn(format, t);
    }

    public void info(String format, Object... args) {
        logger.info(format, args);
    }

    @Data
    private static class UploadFile implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String saveFileName;
        private final long size;
        private final HttpHeaders headers;

        private UploadFile(Part part, String saveFileName) {
            this.saveFileName = saveFileName;
            this.size = part.getSize();
            HttpHeaders headers = new HttpHeaders();
            part.getHeaderNames().forEach(e -> headers.put(e, new ArrayList<>(part.getHeaders(e))));
            this.headers = HttpHeaders.readOnlyHttpHeaders(headers);
        }
    }

    @Data
    private static class EvidenceRequest {
        private final Map<String, String[]> parameters;
        private final HttpHeaders headers;
    }

    @Data
    private static class EvidenceResponse {
        private final HttpStatus httpStatus;
        private final HttpHeaders headers;
    }

}
