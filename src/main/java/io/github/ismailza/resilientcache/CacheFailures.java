/*
 * Copyright 2026 Ismail ZAHIR
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.ismailza.resilientcache;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.serializer.SerializationException;
import tools.jackson.core.JacksonException;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code CacheFailures} classifies a failed cache operation and describes it for a log line without
 * leaking what was cached.
 *
 * <p>Only a connection or timeout message is kept: it is safe, and it is what an operator needs. Any
 * other message may quote a key or value — Jackson's ({@code Cannot deserialize … from String "…"}), or
 * Spring's own ({@code Cached value is not of required type […]: <value>}) — and a cached value may
 * identify a person. For those, only the exception types are kept.</p>
 *
 * @author Ismail ZAHIR
 * @since 0.1.0
 */
final class CacheFailures {

    /** How many causes a description lists; a longer chain is cut. */
    private static final int MAX_CAUSES = 10;

    private CacheFailures() {
    }

    static String describe(RuntimeException exception) {
        if (isUnavailability(exception)) {
            return exception.toString();
        }
        List<String> types = new ArrayList<>();
        boolean serialization = false;
        for (Throwable cause = exception; cause != null && types.size() < MAX_CAUSES; cause = cause.getCause()) {
            types.add(cause.getClass().getName());
            serialization |= cause instanceof SerializationException || cause instanceof JacksonException;
        }
        return (serialization ? "serialization failure" : "cache failure") + " (" + String.join(" <- ", types) + ")";
    }

    /**
     * Whether the store itself is unreachable — a refused connection or a timed-out command — as opposed to
     * one entry failing. Spring Data Redis translates Lettuce's failures to these types.
     */
    static boolean isUnavailability(RuntimeException exception) {
        return exception instanceof DataAccessResourceFailureException || exception instanceof QueryTimeoutException;
    }
}
