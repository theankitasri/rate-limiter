package com.ratelimiter.model;

/**
 * One row of the admin UI "top clients by usage" table.
 */
public record TopClient(String clientId, int used, int limit, int remaining) {
}
