package com.riskyc.common.dto;

import java.util.List;

/**
 * The paginated response shape every admin-service-facing internal list
 * endpoint returns (auth-service's user/report/blocked lists,
 * messaging-service's status list, admin-service's own audit log) — a
 * flattened view of Spring Data's Page that doesn't leak Spring's own
 * Pageable/Sort serialization quirks across a service boundary. Deliberately
 * has no dependency on spring-data-commons (this module stays framework-
 * light — Jackson + JJWT only) — callers build this from their own
 * org.springframework.data.domain.Page via page.getContent()/getTotalElements()/etc.
 */
public record AdminPage<T>(List<T> content, long totalElements, int totalPages, int page, int size) {
}
