package com.example.mcp.confirmation;

/**
 * 人工确认状态机：PENDING → CONFIRMED / REJECTED / EXPIRED；
 * CONFIRMED → EXECUTING（原子 claim）→ EXECUTED。
 */
public enum ConfirmationStatus {
    PENDING,
    CONFIRMED,
    REJECTED,
    EXECUTING,
    EXECUTED,
    EXPIRED
}
