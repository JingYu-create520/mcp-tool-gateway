package com.example.mcp.confirmation;

/**
 * 人工确认状态机：PENDING → CONFIRMED / REJECTED / EXPIRED；CONFIRMED → EXECUTED。
 */
public enum ConfirmationStatus {
    PENDING,
    CONFIRMED,
    REJECTED,
    EXECUTED,
    EXPIRED
}
