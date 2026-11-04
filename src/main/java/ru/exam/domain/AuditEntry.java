package ru.exam.domain;

public record AuditEntry(
    String occurredAt,
    String actor,
    String action,
    String objectType,
    long objectId,
    String result,
    String details) {}
