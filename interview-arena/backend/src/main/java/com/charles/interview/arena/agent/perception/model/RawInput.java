package com.charles.interview.arena.agent.perception.model;

/**
 * 原始输入封装
 */
public record RawInput(
        String text,
        byte[] fileData,
        String fileName,
        String mimeType,
        Long userId,
        Long sessionId,
        InputType inputType
) {
    public enum InputType {
        TEXT, PDF, IMAGE, TOOL_RESULT
    }

    public boolean isPdf() {
        return inputType == InputType.PDF;
    }

    public boolean isImage() {
        return inputType == InputType.IMAGE;
    }

    public boolean isToolResult() {
        return inputType == InputType.TOOL_RESULT;
    }
}
