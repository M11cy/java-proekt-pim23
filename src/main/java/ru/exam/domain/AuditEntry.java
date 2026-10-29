package ru.exam.domain;

public record AuditEntry(
    String occurredAt, String action, String objectType, long objectId, String details) {}
